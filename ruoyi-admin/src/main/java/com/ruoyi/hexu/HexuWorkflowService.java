package com.ruoyi.hexu;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Service
public class HexuWorkflowService {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private static final String AGENT_AGREEMENT="AGENT_AGREEMENT";
    private final HexuStore db;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private HexuPolicies policies;
    @org.springframework.beans.factory.annotation.Value("${hexu.sensitive-key:}") private String sensitiveKey;
    public HexuWorkflowService(HexuStore db){this.db=db;}
    @SuppressWarnings("unchecked")
    private Map<String,Object> currentAgentAgreement(long shop){
        // 申请时只接受当前商城可见的已发布协议版本。
        Object value=(policies==null?new HexuPolicies(db):policies).current(shop).get(AGENT_AGREEMENT);
        require(value instanceof Map,"代理合作协议正文尚未发布");
        return (Map<String,Object>)value;
    }
    private static Map<String,Object> namedFreight(Map<String,Object> source){
        String name=str(source,"name").trim();
        require(!name.isEmpty()&&name.codePointCount(0,name.length())<=80&&name.codePoints().noneMatch(Character::isISOControl),"请填写1至80字的运费模板名称");
        Map<String,Object> freight=new LinkedHashMap<>(source);
        freight.put("name",name);
        return freight;
    }
    private void checkedMarketingSkus(long shop,Object raw){
        if(raw==null)return;
        Collection<?> values;
        if(raw instanceof String)values=Arrays.asList(((String)raw).split(","));
        else if(raw instanceof Collection)values=(Collection<?>)raw;
        else throw new BusinessException("促销商品SKU格式无效");
        for(Object value:values){
            require(value instanceof String,"促销商品SKU格式无效");
            String sku=((String)value).trim();
            if(sku.isEmpty()||"全部商品".equals(sku)||"全部用户".equals(sku))continue;
            require(db.optional("select id from hx_sku where shop_id=? and id=?",shop,sku)!=null,"促销商品SKU不属于当前商城："+sku);
        }
    }
    static List<String> checkedFavoriteIds(Object value){
        require(value instanceof List,"收藏商品格式无效");
        List<?> input=(List<?>)value;
        require(input.size()<=100,"最多收藏100件商品");
        List<String> ids=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Object item:input){
            require(item instanceof String,"收藏商品编号无效");
            String id=(String)item;
            require(!id.trim().isEmpty()&&id.equals(id.trim())&&id.codePointCount(0,id.length())<=128&&id.codePoints().noneMatch(Character::isISOControl)&&seen.add(id),"收藏商品编号无效或重复");
            ids.add(id);
        }
        return ids;
    }
    static Map<String,Object> checkedAddress(Map<String,Object> body){
        require(bool(body,"consent"),"地址用途需单独授权");
        String name=HexuText.trim(str(body,"name")),phone=HexuText.trim(str(body,"phone"));
        String region=HexuText.trim(str(body,"region")),detail=HexuText.trim(str(body,"detail"));
        String usage=HexuText.trim(str(body,"usage"));
        if(usage.isEmpty())usage="家";
        require(!name.isEmpty()&&name.codePointCount(0,name.length())<=30,"收货人请输入1–30个字符");
        require(phone.matches("1[3-9][0-9]{9}"),"请输入正确的手机号码");
        require(region.codePointCount(0,region.length())<=100&&HexuRegions.valid(region),"请选择有效的省、市、区");
        region=HexuRegions.canonical(region);
        require(!detail.isEmpty()&&detail.codePointCount(0,detail.length())<=150,"详细地址请输入1–150个字符");
        require(Arrays.asList("家","公司","学校","其他").contains(usage),"请选择有效的地址用途");
        require(!Arrays.asList(name,region,detail).stream().anyMatch(value->value.codePoints().anyMatch(Character::isISOControl)),"地址信息不能包含控制字符");
        Map<String,Object> checked=new LinkedHashMap<>(body);
        checked.put("name",name);checked.put("phone",phone);checked.put("region",region);checked.put("detail",detail);checked.put("usage",usage);
        return checked;
    }
    public List<Map<String,Object>> dailySales(Actor a,long shop){db.manage(a,shop,"OPERATOR","FINANCE","ORDER");List<Map<String,Object>> days=new ArrayList<>();for(int i=6;i>=0;i--){java.time.LocalDate day=java.time.LocalDate.now().minusDays(i);days.add(db.one("select coalesce(sum(total-refunded),0) amount,count(*) orders from hx_order where shop_id=? and status in ('PAID','SHIPPED','COMPLETED','REFUNDED') and created_at>=? and created_at<?",shop,java.sql.Timestamp.valueOf(day.atStartOfDay()),java.sql.Timestamp.valueOf(day.plusDays(1).atStartOfDay())));days.get(days.size()-1).put("day",day.toString());}return days;}
    public List<Map<String,Object>> reviews(long shop,String sku){return reviews(null,shop,sku);}
    public List<Map<String,Object>> reviews(Actor requester,long shop,String sku){return new HexuReviews(db).list(requester,shop,sku);}
    public List<Map<String,Object>> marketing(Actor a,long shop){
        db.member(a);db.shop(shop);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object>d:db.jdbc().queryForList("select * from hx_document where shop_id=? and kind in ('promotion_rule','bundle') and status='ACTIVE' order by created_at,id",shop)){
            Map<String,Object>b=db.parse(d.get("body_json"));
            if(number(b,"startsAt")>System.currentTimeMillis()||number(b,"expiresAt")<=System.currentTimeMillis())continue;
            String id=str(d,"id");
            long used=db.jdbc().queryForObject("select coalesce(sum(u.qty),0) from hx_marketing_usage u join hx_order o on o.id=u.order_id where u.campaign_id=? and o.status<>'CANCELLED'",Long.class,id);
            long own=db.jdbc().queryForObject("select coalesce(sum(u.qty),0) from hx_marketing_usage u join hx_order o on o.id=u.order_id where u.campaign_id=? and o.buyer_id=? and o.status<>'CANCELLED'",Long.class,id,a.id);
            long quantity=number(b,"quantity"),perMember=number(b,"perMember");
            long remaining=quantity==0?-1:Math.max(0,quantity-used);
            long memberRemaining=perMember==0?-1:Math.max(0,perMember-own);
            Map<String,Object>publicBody=new LinkedHashMap<>();
            for(String key:Arrays.asList("name","type","items","skuIds","qualifierSkuIds","price","threshold","discount","rateBps","startsAt","expiresAt","quantity","perMember"))if(b.containsKey(key))publicBody.put(key,b.get(key));
            result.add(map("id",id,"kind",d.get("kind"),"body",publicBody,"remaining",remaining,"memberRemaining",memberRemaining,"available",remaining!=0&&memberRemaining!=0));
        }
        return result;
    }
    public List<Map<String,Object>> coupons(Actor a,long shop){
        db.member(a);
        List<Map<String,Object>> rows=db.jdbc().queryForList("select * from hx_document where shop_id=? and ((kind='coupon' and status='ACTIVE') or (kind='coupon_claim' and member_id=? and status<>'DELETED')) order by created_at desc",shop,a.id);
        Map<String,Long> issued=new HashMap<>();
        for(Map<String,Object> count:db.jdbc().queryForList("select campaign_ref,count(*) issued_count from hx_document where shop_id=? and kind='coupon_claim' and status<>'DELETED' and campaign_ref is not null group by campaign_ref",shop))issued.put(str(count,"campaign_ref"),number(count,"issued_count"));
        rows.removeIf(row->{if(!"coupon".equals(row.get("kind")))return false;try{new HexuPricing(db).audience(a,shop,db.parse(row.get("body_json")));return false;}catch(BusinessException ignored){return true;}});
        for(Map<String,Object> row:rows){
            Map<String,Object> body=db.parse(row.get("body_json"));
            row.put("body",body);
            if("coupon".equals(row.get("kind")))row.put("remainingQuantity",Math.max(0,number(body,"quantity")-issued.getOrDefault(str(row,"id"),0L)));
            row.remove("reviewer");row.remove("review_note");
        }
        return rows;
    }
    static LocalDate checkinDay(Instant instant){return instant.atZone(BUSINESS_ZONE).toLocalDate();}
    private long checkinCount(long member,long scope,LocalDate day){
        Timestamp start=Timestamp.from(day.atStartOfDay(BUSINESS_ZONE).toInstant());
        Timestamp end=Timestamp.from(day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
        // Check the actual business-day interval so legacy UTC-date references cannot grant a second reward after deployment.
        return db.jdbc().queryForObject("select count(*) from hx_point_log where member_id=? and shop_id=? and kind='CHECKIN' and created_at>=? and created_at<?",Long.class,member,scope,start,end);
    }
    public Map<String,Object> checkinStatus(Actor a,long shop){LocalDate day=checkinDay(Instant.now());Map<String,Object>result=new LinkedHashMap<>();for(long scope:new long[]{0,shop}){long reward=scope==0?db.parameter(0,"points_rule","checkinPoints",5):number(db.config(shop,"points_rule"),"checkinPoints");result.put(scope==0?"platform":"shop",checkinCount(a.id,scope,day)>0?0:reward);}return result;}
    @Transactional public Map<String,Object> checkin(Actor a,long shop){db.member(a);db.shop(shop);db.one("select id from hx_member where id=? for update",a.id);LocalDate day=checkinDay(Instant.now());long awarded=0;for(long scope:new long[]{0,shop}){long reward=scope==0?db.parameter(0,"points_rule","checkinPoints",5):number(db.config(shop,"points_rule"),"checkinPoints");if(reward<=0||checkinCount(a.id,scope,day)>0)continue;db.points(scope,a.id,reward,"CHECKIN",day.toString(),false);awarded+=reward;}require(awarded>0,"今天已签到或签到活动未开启");return map("points",awarded,"date",day.toString());}
    @Transactional public Map<String,Object> recordBrowse(Actor a,long shop,String sku){
        db.member(a);db.shop(shop);
        require(sku!=null&&sku.matches("[A-Za-z0-9_-]{1,64}"),"商品编号无效");
        db.one("select id from hx_sku where shop_id=? and id=? and status='ACTIVE'",shop,sku);
        db.one("select id from hx_member where id=? for update",a.id);
        Map<String,Object> prior=db.optional("select id,body_json from hx_document where shop_id=? and member_id=? and kind='browse_history' and status='ACTIVE' order by created_at desc limit 1 for update",shop,a.id);
        LinkedHashSet<String> ids=new LinkedHashSet<>();ids.add(sku);
        if(prior!=null){Object saved=db.parse(prior.get("body_json")).get("ids");if(saved instanceof List<?>)for(Object value:(List<?>)saved)if(value instanceof String&&((String)value).matches("[A-Za-z0-9_-]{1,64}"))ids.add((String)value);}
        List<String> recent=new ArrayList<>(ids);if(recent.size()>50)recent=recent.subList(0,50);
        if(prior==null)db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values(?,?,?,'browse_history','ACTIVE',?)",id("DOC"),shop,a.id,db.json(map("ids",recent)));
        else db.jdbc().update("update hx_document set body_json=?,updated_at=CURRENT_TIMESTAMP where id=?",db.json(map("ids",recent)),prior.get("id"));
        return map("ids",recent);
    }
    private void documentAccess(Actor a,long shop,String kind){
        if(shop==0){db.platform(a);require(Arrays.asList("points_rule","points_risk").contains(kind),"平台积分配置范围不支持此业务");return;}
        if(Arrays.asList("settlement_account","reconciliation","points_rule","points_risk").contains(kind))db.manage(a,shop,"FINANCE");
        else if(Arrays.asList("agent_application","shop_application","migration","promotion","cross_purchase","downgrade","shop_transfer","downgrade_rule","link_campaign","link_relation","merchant_application","merchant_authorization","security_policy","team_migration","peer_authorization","cross_shop_policy").contains(kind))db.manage(a,shop,"OWNER");
        else if("stocktake".equals(kind))db.manage(a,shop,"WAREHOUSE");
        else if(Arrays.asList("support","review","review_append","return_tracking").contains(kind))db.manage(a,shop,"SUPPORT");
        else db.manage(a,shop,"OPERATOR","CATALOG");
    }
    private static final Set<String> MEMBER_KINDS=new HashSet<>(Arrays.asList("address","profile","cart","favorite","review","review_append","support","agent_application","shop_application","migration","promotion","cross_purchase","settlement_account","privacy_consent","return_tracking"));
    private static final Set<String> CONFIG_KINDS=new HashSet<>(Arrays.asList("decoration","page_schema","category","return_address","freight","coupon","promotion_rule","bundle","group_campaign","invitation_campaign","points_rule","points_risk","assessment_rule","downgrade_rule","link_campaign","notification_template","system_parameter","merchant_application","merchant_authorization","stocktake","downgrade","shop_transfer","reconciliation","security_policy","cross_shop_policy"));
    private void validateAgentApplication(Map<String,Object>b,Map<String,Object>previous,long shop,long member){
        long rank=number(b,"rank");
        require(rank>=1&&rank<=3&&!str(b,"name").trim().isEmpty()&&str(b,"phone").matches("1[3-9][0-9]{9}"),"代理资料或申请职级不完整");
        require(!str(b,"所在县域").trim().isEmpty()&&!str(b,"accountName").trim().isEmpty(),"请填写所在县域和开户姓名");
        require(bool(b,"consent"),"请同意代理合作协议");
        String accountType=str(b,"账户类型");
        require(Arrays.asList("银行卡","微信零钱").contains(accountType),"请选择有效的结算账户类型");
        if("银行卡".equals(accountType)){
            String bank=str(b,"bank");
            if(bank.matches("[1-9][0-9]{15,18}")&&!bank.matches("(.)\\1+"))b.put("bankLength",bank.length());
            else require(previous!=null&&bank.matches("\\*{4}[0-9]{4}")&&bank.equals(str(previous,"bank"))&&number(previous,"bankLength")>=16&&number(previous,"bankLength")<=19&&!str(previous,"_secure_bank").isEmpty(),"请填写16至19位有效银行卡号");
        }
        require(b.get("uploads") instanceof List&& !((List<?>)b.get("uploads")).isEmpty()&&((List<?>)b.get("uploads")).size()<=9,"请上传1至9份证件或营业资料");
        for(Object file:(List<?>)b.get("uploads")){
            require(file instanceof String&&!((String)file).isEmpty(),"证件资料无效");
            require(db.optional("select id from hx_attachment where id=? and shop_id=? and member_id=? and purpose='IDENTITY'",file,shop,member)!=null,"证件资料不存在或不属于申请人");
        }
    }
    private long agentApplicationParent(long member,long shop,Map<String,Object>application){
        Map<String,Object> binding=db.optional("select agent_id from hx_binding where member_id=? and shop_id=?",member,shop);
        Map<String,Object> referral=db.optional("select inviter_agent_id from hx_referral where member_id=? and shop_id=?",member,shop);
        require(referral==null||binding!=null&&number(referral,"inviter_agent_id")==number(binding,"agent_id"),"邀请绑定关系不一致，请先核对");
        if(binding!=null)return number(binding,"agent_id");
        long requested=number(application,"parentId");
        if(requested>0)return requested;
        return number(db.agent(number(db.shop(shop),"owner_id"),shop),"id");
    }
    @Transactional public Map<String,Object> save(Actor a,long shop,String kind,Map<String,Object>b,boolean management){require((management?CONFIG_KINDS:MEMBER_KINDS).contains(kind),"不支持的业务类型");if(!management&&"profile".equals(kind))return new HexuProfileService(db).saveLegacy(a,shop,b);if(management){documentAccess(a,shop,kind);if(shop>0)db.one("select id from hx_shop where id=? for update",shop);}else db.member(a);String doc=str(b,"id");if(doc.isEmpty()&&Arrays.asList("cart","favorite").contains(kind)){Map<String,Object> prior=db.optional("select id from hx_document where member_id=? and shop_id=? and kind=? and status=\'ACTIVE\'",a.id,shop,kind);if(prior!=null)doc=str(prior,"id");}Map<String,Object> old=null;if(!doc.isEmpty()){old=db.one("select * from hx_document where id=? and shop_id=? and kind=? for update",doc,shop,kind);require(management||number(old,"member_id")==a.id,"无权修改其他用户数据");require(Arrays.asList("DRAFT","ACTIVE","REJECTED","SUPPLEMENT").contains(str(old,"status")),"已提交或审核通过的记录不能直接覆盖");}
        HexuAttachmentAssociation.uploads(db,a,shop,kind,b,old,management);
        if(management)HexuAttachmentAssociation.storefront(db,a,shop,kind,b,old);
        if("address".equals(kind))b=checkedAddress(b);
        if("cart".equals(kind))new HexuCartSaveValidation(db).validate(a,shop,b,old);
        if("favorite".equals(kind)){b=new LinkedHashMap<>(b);b.put("ids",checkedFavoriteIds(b.get("ids")));}
        boolean bindAgentAgreement=false;
        if("agent_application".equals(kind)){
            Map<String,Object> previous=old==null?null:db.parse(old.get("body_json"));
            validateAgentApplication(b,previous,shop,a.id);
            require(db.agent(a.id,shop)==null,"您已经是本店代理");
            b=new LinkedHashMap<>(b);
            b.put("parentId",agentApplicationParent(a.id,shop,b));
            if(previous==null||str(previous,"agreementVersion").isEmpty()){
                bindAgentAgreement=true;
                // 首次申请或历史无版本申请须确认当前协议；已有版本的补充资料沿用原签署版本。
                if(previous!=null)require(bool(b,"agreementReconsent"),"请阅读并同意当前代理合作协议");
                Map<String,Object> current=currentAgentAgreement(shop);
                require(str(b,"agreementVersion").equals(str(current,"version"))&&str(b,"agreementPolicyId").equals(str(current,"id")),"代理协议已更新，请阅读最新版本后重新确认");
                b=new LinkedHashMap<>(b);
                b.put("agreementVersion",current.get("version"));
                b.put("agreementPolicyId",current.get("id"));
                b.put("agreementSandbox",Boolean.TRUE.equals(current.get("sandbox")));
            }else{
                b=new LinkedHashMap<>(b);
                b.put("agreementVersion",previous.get("agreementVersion"));
                b.put("agreementPolicyId",previous.get("agreementPolicyId"));
                b.put("agreementSandbox",previous.get("agreementSandbox"));
            }
        }
        if("migration".equals(kind)){
            db.one("select id from hx_member where id=? for update",a.id);
            require(db.jdbc().queryForObject("select count(*) from hx_document where shop_id=? and member_id=? and kind='migration' and status in ('PENDING','SUPPLEMENT','SCHEDULED') and id<>?",Long.class,shop,a.id,doc)==0,"已有进行中的迁移申请，请等待审核或补充原申请");
            b=new LinkedHashMap<>(b);b.put("effectiveAt",migrationDate(b));
        }
        if("support".equals(kind))b=new HexuSupport(db).prepare(a,shop,b);
        if("review".equals(kind))b=new HexuReviews(db).original(a,shop,b,old);
        if("review_append".equals(kind))b=new HexuReviews(db).append(a,shop,b,old);
        if("link_campaign".equals(kind)){if(old!=null){Map<String,Object>combined=new LinkedHashMap<>(db.parse(old.get("body_json")));combined.putAll(b);b=combined;}new HexuLinkCampaign(db).validate(a,shop,b,old);b=new LinkedHashMap<>(b);b.remove("campaignId");if(old!=null){Map<String,Object>previous=db.parse(old.get("body_json"));b.put("campaignId",str(previous,"campaignId").isEmpty()?old.get("id"):previous.get("campaignId"));b.put("previousVersionId",old.get("id"));doc="";}}
        if("group_campaign".equals(kind))new HexuGroupPurchase(db).validate(shop,b);
        if("invitation_campaign".equals(kind))HexuInvitationRewards.validate(b);
        HexuPricing.validate(kind,b);
        if("bundle".equals(kind))for(Map<String,Object>item:(List<Map<String,Object>>)b.get("items"))db.one("select id from hx_sku where shop_id=? and id=?",shop,str(item,"skuId"));
        if(Arrays.asList("promotion_rule","bundle").contains(kind)){
            checkedMarketingSkus(shop,b.get("skuIds"));
            if("bundle".equals(kind))checkedMarketingSkus(shop,b.get("qualifierSkuIds"));
        }
        if("return_address".equals(kind)){require(!str(b,"name").trim().isEmpty()&&!str(b,"phone").trim().isEmpty()&&str(b,"address").trim().length()>=5,"请填写退货联系人、电话和详细地址");}
        if("cross_purchase".equals(kind)){HexuCrossPurchase.validate(db,shop,b);require(db.agent(a.id,number(b,"sourceShopId"))!=null&&number(b,"sourceShopId")!=shop,"请提供有效的原商城代理身份");}
        if("assessment_rule".equals(kind))HexuAssessment.validate(b);
        if("downgrade_rule".equals(kind)){HexuDowngrades.validate(b);if(old!=null){doc="";b=new LinkedHashMap<>(b);b.put("previousRuleId",old.get("id"));}}
        if("promotion".equals(kind)){Map<String,Object>assessment=new HexuAssessment(db).evaluate(a.id,shop);require(bool(assessment,"eligible")&&number(b,"rank")==number(assessment,"targetRank"),"尚未达到已配置的晋升条件");require(db.jdbc().queryForObject("select count(*) from hx_document where shop_id=? and member_id=? and kind='promotion' and status in ('PENDING','SCHEDULED')",Long.class,shop,a.id)==0,"已有进行中的晋升申请");b=new LinkedHashMap<>(b);b.put("assessment",assessment);b.put("fromRank",assessment.get("rank"));}
        if("cross_shop_policy".equals(kind))require(Arrays.asList("RETURN_HOME","ALLOW_RETAIL").contains(str(b,"mode")),"跨店模式无效");
        if("page_schema".equals(kind)){require(b.get("screens") instanceof Map,"页面配置必须包含 screens 对象");if(b.containsKey("catalog"))require(b.get("catalog") instanceof List,"页面目录必须为数组");require(((Map<?,?>)b.get("screens")).size()<=256,"页面配置数量不能超过256");}
        if("decoration".equals(kind)){if(b.get("banners")!=null)require(b.get("banners") instanceof List&&((List<?>)b.get("banners")).size()<=20,"轮播配置最多20条");if(b.get("categories")!=null)require(b.get("categories") instanceof List&&((List<?>)b.get("categories")).size()<=20,"首页分类最多20条");if(b.get("homeProducts")!=null)require(b.get("homeProducts") instanceof List&&((List<?>)b.get("homeProducts")).size()<=50,"首页推荐商品最多50个");}
        if("freight".equals(kind)){b=namedFreight(b);HexuFreight.validate(b);}
        if("category".equals(kind))HexuProductData.categories(b);
        if("stocktake".equals(kind)){
            require(b.get("items") instanceof List,"请提交盘点明细");
            List<?> input=(List<?>)b.get("items");
            require(!input.isEmpty()&&input.size()<=500,"盘点明细数量不合法");
            List<Map<String,Object>> checked=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            for(Object item:input){
                require(item instanceof Map,"盘点明细格式无效");
                Map<String,Object> i=(Map<String,Object>)item;
                String sku=str(i,"skuId");
                require(!sku.isEmpty()&&seen.add(sku),"盘点商品不能为空或重复");
                require(i.containsKey("actual")&&i.get("actual")!=null&&!String.valueOf(i.get("actual")).trim().isEmpty(),"请填写实盘库存");
                Map<String,Object> p=db.one("select * from hx_sku where shop_id=? and id=?",shop,sku);
                if(i.containsKey("expectedAvailable")||i.containsKey("expectedLocked")){
                    require(i.get("expectedAvailable")!=null&&i.get("expectedLocked")!=null,"盘点库存快照不完整");
                    require(number(i,"expectedAvailable")==number(p,"available")&&number(i,"expectedLocked")==number(p,"locked"),"库存已变化，请重新读取盘点数据");
                }
                long actual=number(i,"actual");
                require(actual>=number(p,"locked")&&actual<=Integer.MAX_VALUE,"实盘库存不能小于已锁定库存");
                checked.add(map("skuId",sku,"name",p.get("name"),"available",p.get("available"),"locked",p.get("locked"),"actual",actual,"delta",actual-number(p,"available")-number(p,"locked")));
            }
            b=new LinkedHashMap<>(b);
            b.put("items",checked);
        }
        if("points_risk".equals(kind))HexuPointRisk.validate(b);
        if("points_risk".equals(kind))require(number(b,"singleLimit")>0&&number(b,"dailyLimit")>=number(b,"singleLimit")&&number(b,"monthlyLimit")>=number(b,"dailyLimit"),"积分风控限额需依次递增且为正数");
        if("points_rule".equals(kind))require(number(b,"purchaseRate")>=0&&number(b,"purchaseRate")<=100&&number(b,"deductionPercent")>=0&&number(b,"deductionPercent")<=20&&number(b,"checkinPoints")>=0&&number(b,"checkinPoints")<=100,"积分赠送或抵扣参数不合法");
        if("points_rule".equals(kind)&&b.containsKey("reviewPoints"))require(number(b,"reviewPoints")>=0&&number(b,"reviewPoints")<=100,"评价积分须为0至100");
        if("points_rule".equals(kind)&&b.containsKey("expiryDays"))require(number(b,"expiryDays")>=0&&number(b,"expiryDays")<=36500,"积分有效期须为0至36500天");
        if("points_rule".equals(kind)&&b.containsKey("pointsPerYuan"))require(number(b,"pointsPerYuan")>0&&number(b,"pointsPerYuan")<=100000,"抵扣1元所需积分必须为1至100000");
        if("notification_template".equals(kind))HexuNotifications.validate(b);
        if(old!=null&&Arrays.asList("system_parameter","notification_template").contains(kind)){b=new LinkedHashMap<>(b);b.put("previousVersionId",old.get("id"));doc="";}
        if("system_parameter".equals(kind))require(number(b,"unpaidMinutes")>=1&&number(b,"unpaidMinutes")<=1440&&number(b,"autoReceiveDays")>=1&&number(b,"autoReceiveDays")<=90&&number(b,"afterSalesDays")>=1&&number(b,"afterSalesDays")<=365,"交易时限不合法");
        if("address".equals(kind)&&bool(b,"primary")){for(Map<String,Object>d:db.jdbc().queryForList("select * from hx_document where shop_id=? and member_id=? and kind='address' and status='ACTIVE'",shop,a.id)){Map<String,Object>v=db.parse(d.get("body_json"));v.put("primary",false);db.jdbc().update("update hx_document set body_json=? where id=?",db.json(v),d.get("id"));}}
        if("settlement_account".equals(kind))b=validateSettlementAccount(b,old);
        if(Arrays.asList("agent_application","settlement_account","shop_application").contains(kind)){b=new LinkedHashMap<>(b);b.keySet().removeIf(k->k.startsWith("_secure_"));if(old!=null){Map<String,Object>prior=db.parse(old.get("body_json"));for(String key:prior.keySet())if(key.startsWith("_secure_")&&Objects.equals(b.get(key.substring(8)),prior.get(key.substring(8))))b.put(key,prior.get(key));}SensitiveFields.seal(b,sensitiveKey);}
        String status=management?"ACTIVE":Arrays.asList("address","profile","cart","favorite","privacy_consent","return_tracking").contains(kind)?"ACTIVE":"PENDING";
        if("shop_transfer".equals(kind)){b=new HexuShopLifecycle(db).prepare(a,shop,b);status="PENDING";}
        if("stocktake".equals(kind))status="PENDING";
        if(doc.isEmpty()){doc=id("DOC");db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values(?,?,?,?,?,?)",doc,shop,a.id,kind,status,db.json(b));}else db.jdbc().update("update hx_document set body_json=?,status=?,updated_at=CURRENT_TIMESTAMP where id=?",db.json(b),status,doc);
        if(bindAgentAgreement){
            // 首次申请和历史补签均与协议同意记录在同一事务落库，重试不重复写入。
            String version=str(b,"agreementVersion");
            if(db.optional("select member_id from hx_policy_consent where member_id=? and shop_id=? and policy_type=? and version=?",a.id,shop,AGENT_AGREEMENT,version)==null)
                db.jdbc().update("insert into hx_policy_consent(member_id,shop_id,policy_type,version,source) values(?,?,?,?,?)",a.id,shop,AGENT_AGREEMENT,version,bool(b,"agreementSandbox")?"AGENT_APPLICATION_SANDBOX":"AGENT_APPLICATION");
        }
        if(Arrays.asList("system_parameter","decoration","page_schema","category","points_rule","points_risk","assessment_rule","cross_shop_policy","return_address").contains(kind))db.jdbc().update("update hx_document set status='ARCHIVED' where shop_id=? and kind=? and id<>? and status='ACTIVE'",shop,kind,doc);
        if("notification_template".equals(kind))for(Map<String,Object>prior:db.jdbc().queryForList("select id,body_json from hx_document where shop_id=? and kind='notification_template' and id<>? and status='ACTIVE'",shop,doc))if(str(b,"event").equals(str(db.parse(prior.get("body_json")),"event")))db.jdbc().update("update hx_document set status='ARCHIVED' where id=?",prior.get("id"));
        if("link_campaign".equals(kind))db.jdbc().update("update hx_document set status='ARCHIVED' where shop_id=? and kind='link_campaign' and id<>? and status='ACTIVE'",shop,doc);
        db.audit(a,shop,"SAVE_"+kind,doc,old,b);
        if("support".equals(kind))db.message(shop,a.id,"客服留言已收到",old==null?doc:str(old,"id"));
        return db.one("select * from hx_document where id=?",doc);
    }
    private static Map<String,Object> checkedReviewReason(Map<String,Object> body,String decision){
        require(!body.containsKey("reason")||body.get("reason") instanceof String,"审核原因须为文本");
        String raw=body.containsKey("reason")?(String)body.get("reason"):"";
        require(!raw.codePoints().anyMatch(point->Character.isISOControl(point)&&point!='\n'&&point!='\r'&&point!='\t'),"审核原因不能包含非法控制字符");
        String reason=HexuText.trim(raw);require(reason.codePointCount(0,reason.length())<=500,"审核原因最多500个字符");
        require("APPROVED".equals(decision)||!reason.isEmpty(),"请填写处理原因");
        Map<String,Object> checked=new LinkedHashMap<>(body);checked.put("reason",reason);return checked;
    }
    static String reviewNotificationTitle(String kind,String decision){
        String subject="review".equals(kind)?"商品评价":"review_append".equals(kind)?"商品追评":"support".equals(kind)?"客服留言":null;
        if(subject==null){
            String result;
            switch(decision){
                case "APPROVED":result="审核通过";break;
                case "REJECTED":result="审核未通过";break;
                case "SUPPLEMENT":result="待补充资料";break;
                default:result=decision;
            }
            return "申请处理结果："+result;
        }
        return subject+("SUPPLEMENT".equals(decision)?"待补充":"REJECTED".equals(decision)?"未通过审核":"support".equals(kind)?"已处理":"审核通过");
    }
    private static String migrationDate(Map<String,Object> body){
        if(!body.containsKey("effectiveAt"))return "";
        require(body.get("effectiveAt") instanceof String,"迁移生效日期须为文本，留空立即生效");
        String value=str(body,"effectiveAt").trim();if(value.isEmpty())return "";
        try{
            if(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))value+=" 00:00:00";
            require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?"),"迁移生效日期格式无效");
            java.time.format.DateTimeFormatter format=new java.time.format.DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm:ss").optionalStart().appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND,0,9,true).optionalEnd().toFormatter().withResolverStyle(java.time.format.ResolverStyle.STRICT);
            java.time.LocalDateTime date=java.time.LocalDateTime.parse(value,format);
            require(date.getYear()>=1&&date.getYear()<=9999,"迁移生效日期超出范围");return java.sql.Timestamp.valueOf(date).toString();
        }catch(java.time.DateTimeException e){throw new BusinessException("请填写真实的迁移生效日期（YYYY-MM-DD），留空立即生效");}
    }
    private Map<String,Object> validateSettlementAccount(Map<String,Object> input,Map<String,Object> old){
        String channel=str(input,"channel"),name=str(input,"accountName").trim();
        require(Arrays.asList("BALANCE","WECHAT","BANK").contains(channel),"请选择有效的结算渠道");
        require(input.get("accountName") instanceof String&&!name.isEmpty()&&name.codePointCount(0,name.length())<=40&&!name.codePoints().anyMatch(Character::isISOControl),"请填写1至40个字符的开户姓名");
        Map<String,Object> checked=map("channel",channel,"accountName",name);
        if("BANK".equals(channel)){
            String bank=str(input,"bank"),bankName=str(input,"bankName").trim();
            Map<String,Object> previous=old==null?Collections.emptyMap():db.parse(old.get("body_json"));
            boolean unchangedMask=bank.matches("\\*{4}[0-9]{4}")&&bank.equals(str(previous,"bank"))&&!str(previous,"_secure_bank").isEmpty();
            require(input.get("bank") instanceof String&&(unchangedMask||bank.matches("[1-9][0-9]{15,18}")&&!bank.matches("(.)\\1+")),"请填写16至19位有效银行卡号");
            require(input.get("bankName") instanceof String&&!bankName.isEmpty()&&bankName.codePointCount(0,bankName.length())<=60&&!bankName.codePoints().anyMatch(Character::isISOControl),"请填写有效的开户银行");
            checked.put("bank",bank);checked.put("bankName",bankName);
        }
        return checked;
    }
    @Transactional public Map<String,Object> saveCatalogSettings(Actor a,long shop,Map<String,Object>b){
        db.manage(a,shop,"CATALOG");
        Map<String,Object> category=map("items",b.get("categories"));
        HexuProductData.categories(category);
        if(!str(b,"categoryId").isEmpty())category.put("id",b.get("categoryId"));
        if(!b.containsKey("freight"))return map("category",save(a,shop,"category",category,true));
        require(b.get("freight") instanceof Map,"请填写运费模板");
        Map<String,Object> freight=namedFreight((Map<String,Object>)b.get("freight"));
        HexuFreight.validate(freight);
        return map("category",save(a,shop,"category",category,true),"freight",save(a,shop,"freight",freight,true));
    }
    public List<Map<String,Object>> list(Actor a,long shop,String kind,boolean management){
        if(management)documentAccess(a,shop,kind);else db.member(a);
        // 用户地址只展示有效记录；其他文档及管理端历史读取保持原查询语义。
        String visibleAddress=!management&&"address".equals(kind)?" and status='ACTIVE'":"";
        List<Map<String,Object>> list=db.jdbc().queryForList("select * from hx_document where shop_id=? and kind=?"+(management?"":" and member_id="+a.id)+visibleAddress+" order by created_at desc limit 200",shop,kind);
        for(Map<String,Object>d:list){Map<String,Object>body=db.parse(d.get("body_json"));SensitiveFields.redact(body);d.put("body",body);d.remove("body_json");}
        return list;
    }
    @Transactional public Map<String,Object> remove(Actor a,String id){Map<String,Object>d=db.one("select * from hx_document where id=? for update",id);require(!a.staff&&number(d,"member_id")==a.id&&Arrays.asList("address","favorite","cart").contains(str(d,"kind")),"此业务记录不能删除");
        // 同一用户重复删除已软删记录直接回显终态，避免新增重复归档审计。
        if("DELETED".equals(str(d,"status")))return map("id",id,"status","DELETED");
        db.jdbc().update("update hx_document set status='DELETED' where id=?",id);db.audit(a,number(d,"shop_id"),"ARCHIVE",id,d,null);return map("id",id,"status","DELETED");}
    @Transactional public Map<String,Object> review(Actor a,String id,Map<String,Object>body){Map<String,Object>d=db.one("select * from hx_document where id=? for update",id);long shop=number(d,"shop_id"),member=number(d,"member_id");String kind=str(d,"kind");db.manage(a,shop,"settlement_account".equals(kind)?"FINANCE":"support".equals(kind)?"SUPPORT":"OWNER");require(Arrays.asList("PENDING","SUPPLEMENT","REVIEW_REQUIRED").contains(str(d,"status")),"记录已处理");String decision=str(body,"decision");require(Arrays.asList("APPROVED","REJECTED","SUPPLEMENT").contains(decision),"审核结果无效");require("APPROVED".equals(decision)||!str(body,"reason").isEmpty(),"请填写处理原因");Map<String,Object>b=db.parse(d.get("body_json"));
        body=checkedReviewReason(body,decision);
        if("APPROVED".equals(decision)){
            if("link_relation".equals(kind)){require(str(body,"reason").trim().length()>=2,"请填写营销关系复核意见");new HexuLinkCampaign(db).reviewRelation(a,shop,b);}
            else if("agent_application".equals(kind)){
                validateAgentApplication(b,b,shop,member);
                require(db.agent(member,shop)==null,"申请人已经是本店代理");
                db.one("select id from hx_member where id=? for update",member);
                long parent=agentApplicationParent(member,shop,b);
                Map<String,Object> pa=db.one("select * from hx_agent where id=? and shop_id=? and status='ACTIVE'",parent,shop);
                long rank=number(b,"rank");
                require(number(pa,"rank_no")>=rank,"申请职级不能超过上级，需通过晋升流程处理");
                b.put("parentId",parent);
                db.jdbc().update("update hx_document set body_json=? where id=?",db.json(b),id);
                body.put("resolvedParentId",parent);
                db.jdbc().update("insert into hx_agent(member_id,shop_id,parent_id,rank_no) values(?,?,?,?)",member,shop,parent,rank);
                if(body.containsKey("regions")){
                    require(body.get("regions") instanceof List,"经营区域格式无效");
                    List<?> regions=(List<?>)body.get("regions");
                    if(!regions.isEmpty()){
                        long agentId=number(db.one("select id from hx_agent where member_id=? and shop_id=?",member,shop),"id");
                        new HexuAgentDirectory(db).setRegions(a,shop,agentId,regions,"代理申请审核确认经营区域");
                    }
                }
            }
            else if("migration".equals(kind)){if(number(b,"targetShopId")!=shop)db.platform(a);String date=migrationDate(b);b.put("effectiveAt",date);long effective=date.isEmpty()?0:java.sql.Timestamp.valueOf(date).getTime();if(effective>System.currentTimeMillis()){decision="SCHEDULED";db.jdbc().update("update hx_document set body_json=? where id=?",db.json(b),id);}else applyBinding(a,d,b);}
            else if("cross_purchase".equals(kind)){if(body.containsKey("approvedExpiresAt")){long expires=number(body,"approvedExpiresAt");require(expires>System.currentTimeMillis()&&expires<=number(b,"expiresAt"),"审核只能收紧申请的有效期");b.put("requestedExpiresAt",b.get("expiresAt"));b.put("expiresAt",expires);}if(body.get("approvedItems") instanceof List){List<?> approved=(List<?>)body.get("approvedItems");require(!approved.isEmpty(),"授权商品不能为空");List<Map<String,Object>>requested=(List<Map<String,Object>>)b.get("items");Set<String>seen=new HashSet<>();for(Object raw:approved){require(raw instanceof Map,"授权商品格式无效");Map<String,Object>item=(Map<String,Object>)raw;require(seen.add(str(item,"skuId"))&&number(item,"qty")>0,"授权商品重复或数量无效");Map<String,Object>original=requested.stream().filter(x->str(x,"skuId").equals(str(item,"skuId"))).findFirst().orElseThrow(()->new BusinessException("不能授权申请之外的商品"));require(number(item,"qty")<=number(original,"qty"),"授权数量不能超过申请范围");}b.put("requestedItems",requested);b.put("items",approved);}HexuCrossPurchase.validate(db,shop,b);Map<String,Object> source=db.agent(member,number(b,"sourceShopId"));require(source!=null&&number(b,"sourceShopId")!=shop,"请提供有效的原商城代理身份");long expires=number(b,"expiresAt");require(expires>System.currentTimeMillis()&&expires<=System.currentTimeMillis()+366L*86400000,"跨店采购授权有效期须在一年以内");b.put("authorizedRank",source.get("rank_no"));db.jdbc().update("update hx_document set body_json=? where id=?",db.json(b),id);}
            else if("shop_transfer".equals(kind)){db.platform(a);require(bool(body,"handoverConfirmed")&&!str(body,"reason").trim().isEmpty(),"请确认完整交接清单并填写平台审核意见");b.put("documentId",id);long effective=number(b,"effectiveTimestamp");db.jdbc().update("update hx_document set body_json=? where id=?",db.json(b),id);if(effective>System.currentTimeMillis())decision="SCHEDULED";else{new HexuShopLifecycle(db).apply(a,shop,b);decision="APPLIED";}}
            else if("downgrade".equals(kind)){require(!str(body,"reason").trim().isEmpty(),"请记录降级审核及处置原因");Map<String,Object>evidence=new HexuDowngrades(db).reviewEvidence(member,shop,b);String at=str(body,"effectiveAt");long effective=at.isEmpty()?System.currentTimeMillis():java.sql.Timestamp.valueOf(at.length()==10?at+" 00:00:00":at).getTime();require(effective>=System.currentTimeMillis()-60000,"降级不得追溯生效");b.put("reviewEvidence",evidence);b.put("effectiveAt",effective);db.jdbc().update("update hx_document set body_json=? where id=?",db.json(b),id);if(effective>System.currentTimeMillis())decision="SCHEDULED";else{new HexuDowngrades(db).apply(member,shop,b);decision="APPLIED";}}
            else if("promotion".equals(kind)){Map<String,Object>assessment=new HexuAssessment(db).evaluate(member,shop);require(bool(assessment,"eligible")&&number(b,"fromRank")==number(assessment,"rank"),"当前考核或职级已变动，请重新核对");String at=str(body,"effectiveAt");long effective=at.isEmpty()?System.currentTimeMillis():java.sql.Timestamp.valueOf(at.length()==10?at+" 00:00:00":at).getTime();require(effective>=System.currentTimeMillis()-60000,"晋升不得追溯生效");b.put("effectiveAt",effective);b.put("reviewAssessment",assessment);db.jdbc().update("update hx_document set body_json=? where id=?",db.json(b),id);if(effective>System.currentTimeMillis())decision="SCHEDULED";else applyPromotion(member,shop,b);}
            else if("shop_application".equals(kind)){db.platform(a);Map<String,Object>ag=db.agent(member,shop);require(ag!=null&&number(ag,"rank_no")>=2,"不具备开店资格");String county=str(b,"county");require(!county.isEmpty()&&!str(b,"name").isEmpty(),"请补全县域和商城名称");if(number(ag,"rank_no")==2)require(db.jdbc().queryForObject("select count(*) from hx_agent a join hx_shop s on s.id=a.shop_id where a.rank_no=3 and a.status='ACTIVE' and s.county=?",Integer.class,county)==0,"县域已有总代理，分货中心不可申请新商城");db.insert("insert into hx_shop(name,county,kind,owner_id,payment_ready,status,settings) values(?,?,'DEALER',?,false,'ONBOARDING','{}')",str(b,"name"),county,member);}
            else if("stocktake".equals(kind)){require(!str(body,"reason").trim().isEmpty(),"请说明盘点差异原因");List<Map<String,Object>>items=(List<Map<String,Object>>)b.get("items");items.sort(Comparator.comparing(i->str(i,"skuId")));for(Map<String,Object>i:items){Map<String,Object>p=db.one("select * from hx_sku where shop_id=? and id=? for update",shop,str(i,"skuId"));require(number(p,"available")==number(i,"available")&&number(p,"locked")==number(i,"locked"),"盘点后库存已变动，请重新盘点");if(number(i,"delta")!=0)db.inventory(shop,str(i,"skuId"),Math.toIntExact(number(i,"delta")),0,"STOCKTAKE",id);}}
            else if("review".equals(kind)){new HexuReviews(db).approve(d);String orderId=str(b,"orderId");Map<String,Object>snapshot=db.parse(db.one("select snapshot_json from hx_order_line where order_id=? and sku_id=?",orderId,str(b,"skuId")).get("snapshot_json"));long platformReward=snapshot.containsKey("reviewPoints")?number(snapshot,"reviewPoints"):10,shopReward=number(snapshot,"shopReviewPoints");require(platformReward>=0&&platformReward<=100&&shopReward>=0&&shopReward<=100,"评价积分配置错误");if(platformReward>0)db.awardPoints(0,member,platformReward,"REVIEW_REWARD",orderId,snapshot.containsKey("pointsExpiryDays")?number(snapshot,"pointsExpiryDays"):365);if(shopReward>0)db.awardPoints(shop,member,shopReward,"REVIEW_REWARD",orderId,number(snapshot,"shopPointsExpiryDays"));db.jdbc().update("update hx_order set points_earned=points_earned+?,shop_points_earned=shop_points_earned+? where id=?",platformReward,shopReward,orderId);}
            else if("review_append".equals(kind))new HexuReviews(db).approve(d);
            else require(Arrays.asList("settlement_account","support","cross_purchase").contains(kind),"此业务需专门的执行流程，不能直接审核生效");
        }
        db.jdbc().update("update hx_document set status=?,reviewer=?,review_note=?,updated_at=CURRENT_TIMESTAMP where id=?",decision,a.id,str(body,"reason"),id);db.audit(a,shop,"REVIEW_"+kind,id,d,body);new HexuNotifications(db).emit(shop,member,HexuNotifications.reviewEvent(kind),reviewNotificationTitle(kind,decision),id);return db.one("select * from hx_document where id=?",id);
    }
    @Transactional public Map<String,Object> revokeCrossPurchase(Actor a,String id,Map<String,Object> body){
        long shop=number(body,"shopId");
        db.manage(a,shop,"OWNER");
        Map<String,Object> d=db.one("select * from hx_document where id=? and shop_id=? for update",id,shop);
        require("cross_purchase".equals(str(d,"kind"))&&"APPROVED".equals(str(d,"status")),"仅可撤销已批准的跨店采购授权");
        String reason=str(body,"reason").trim();
        require(reason.length()>=2&&reason.length()<=500,"撤销原因须为2–500个字符");
        long revokedAt=System.currentTimeMillis();
        Map<String,Object> details=db.parse(d.get("body_json"));
        details.put("revokeReason",reason);details.put("revokedAt",revokedAt);details.put("revokedBy",a.id);
        db.jdbc().update("update hx_document set status='REVOKED',body_json=?,updated_at=CURRENT_TIMESTAMP where id=?",db.json(details),id);
        db.audit(a,shop,"REVOKE_cross_purchase",id,d,map("status","REVOKED","reason",reason,"revokedAt",revokedAt,"revokedBy",a.id));
        return db.one("select * from hx_document where id=?",id);
    }
    @Transactional public Map<String,Object> claim(Actor a,long shop,String campaign){db.member(a);Map<String,Object>c=db.one("select * from hx_document where id=? and shop_id=? and kind='coupon' and status='ACTIVE' for update",campaign,shop);Map<String,Object> b=db.parse(c.get("body_json"));require(number(b,"expiresAt")>System.currentTimeMillis(),"优惠券已过期");new HexuPricing(db).audience(a,shop,b);require(db.jdbc().queryForObject("select count(*) from hx_document where kind='coupon_claim' and status<>'DELETED' and campaign_ref=?",Integer.class,campaign)<number(b,"quantity"),"优惠券已领完");require(db.jdbc().queryForObject("select count(*) from hx_document where kind='coupon_claim' and status<>'DELETED' and campaign_ref=? and member_id=?",Integer.class,campaign,a.id)==0,"您已领取该优惠券");String ref=id("CP");b.put("campaignId",campaign);db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json,campaign_ref) values(?,?,?,'coupon_claim','AVAILABLE',?,?)",ref,shop,a.id,db.json(b),campaign);return db.one("select * from hx_document where id=?",ref);}
    @Transactional public Map<String,Object> rule(Actor a,Map<String,Object>b){db.platform(a);for(String key:Arrays.asList("cloud_bps","center_bps","owner_bps"))require(number(b,key)>=0&&number(b,key)<=10000,"分红比例范围为0至10000基点");java.sql.Timestamp effective=java.sql.Timestamp.valueOf(str(b,"effectiveAt"));require(effective.getTime()>=System.currentTimeMillis()-60000,"规则不得追溯生效");long id=db.insert("insert into hx_rule(cloud_bps,center_bps,owner_bps,effective_at,created_by) values(?,?,?,?,?)",number(b,"cloud_bps"),number(b,"center_bps"),number(b,"owner_bps"),effective,a.id);db.audit(a,0,"RULE_VERSION",String.valueOf(id),null,b);return db.one("select * from hx_rule where id=?",id);}
    public Map<String,Object> dashboard(Actor a,long shop){db.manage(a,shop,"OPERATOR","FINANCE","ORDER");return map("activeShops",db.jdbc().queryForObject("select count(*) from hx_shop where status='ACTIVE' and kind<>'WHOLESALE'"+(a.platform?"":" and owner_id="+a.id),Long.class),"activeProducts",db.jdbc().queryForObject("select count(*) from hx_sku where shop_id=? and status='ACTIVE'",Long.class,shop),"newCustomers",db.jdbc().queryForObject("select count(*) from hx_binding where shop_id=? and bound_at>=?",Long.class,shop,java.sql.Timestamp.valueOf(java.time.LocalDate.now().atStartOfDay())),"history",dailySales(a,shop),"pendingRefunds",db.jdbc().queryForObject("select count(*) from hx_refund where shop_id=? and status in ('PENDING','WAIT_RETURN','APPROVED','WAIT_EXCHANGE')",Long.class,shop),"orders",db.jdbc().queryForObject("select count(*) from hx_order where shop_id=?",Long.class,shop),"sales",db.jdbc().queryForObject("select coalesce(sum(total-refunded),0) from hx_order where shop_id=? and status in ('PAID','SHIPPED','COMPLETED','REFUNDED')",Long.class,shop),"pendingShip",db.jdbc().queryForObject("select count(*) from hx_order o where shop_id=? and status='PAID' and not exists(select 1 from hx_group_member m join hx_group_buy g on g.id=m.group_id where m.order_id=o.id and g.status<>'FORMED')",Long.class,shop),"stockWarnings",db.jdbc().queryForObject("select count(*) from hx_sku where shop_id=? and available<=warning_qty",Long.class,shop),"agents",db.jdbc().queryForObject("select count(*) from hx_agent where shop_id=? and status='ACTIVE'",Long.class,shop));}
    @Transactional public Map<String,Object> createReport(Actor a,long shop,Map<String,Object>b){return new HexuReports(db).create(a,shop,b);}
    public List<Map<String,Object>> reportQueue(){return new HexuReports(db).queue();}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ) public void processReport(String id){new HexuReports(db).process(id);}
    @Transactional public void failReport(String id,String reason){new HexuReports(db).fail(id,reason);}
    @Transactional public Map<String,Object> auditReportExport(Actor a,String id,String dimension){return new HexuReports(db).exportAudit(a,id,dimension);}
    @Transactional public Map<String,Object> reviewPointFreeze(Actor a,String id,Map<String,Object>b){return new HexuPointRisk(db).review(a,id,b);}
    @Transactional public Object exportAudit(Actor a,long shop,Map<String,Object>b){db.manage(a,shop,"FINANCE","OPERATOR","CATALOG","ORDER","WAREHOUSE","SUPPORT");require(number(b,"rowCount")>=0&&number(b,"rowCount")<=50000,"导出数量不合法");String ref=id("EXP");db.audit(a,shop,"DATA_EXPORT",ref,null,map("resource",str(b,"resource"),"rowCount",number(b,"rowCount"),"filter",str(b,"filter")));return map("reference",ref);}
    @Transactional public Object joinLink(Actor a,long shop){return new HexuLinkCampaign(db).join(a,shop);}
 public List<Map<String,Object>> statementLines(Actor a,long shop,String batch){return new HexuReconciliation(db).lines(a,shop,batch);}
 public List<Map<String,Object>> managementList(Actor a,long shop,String resource){if(Arrays.asList("earnings","withdrawals","ledger","points","pointLedger","pointBatches","channelEvents","groupRefunds","reconciliationBatches","reconciliationLines","reconciliationAdjustments","financeSummary","earningSummary","linkRewards","pointRiskCases","pointTransfers","pointTransferSummary").contains(resource))db.manage(a,shop,"FINANCE");else if("agents".equals(resource))db.manage(a,shop,"OPERATOR","FINANCE");else if("inventory".equals(resource))db.manage(a,shop,"WAREHOUSE");else if("refunds".equals(resource))db.manage(a,shop,"SUPPORT","WAREHOUSE");else if("audit".equals(resource)||"staff".equals(resource))db.manage(a,shop,"OWNER");else db.manage(a,shop,"OPERATOR");if("refunds".equals(resource))return new HexuReadModels(db).refunds(a,shop,true);if("staff".equals(resource))return new HexuOperations(db).staff(a,shop);if("audit".equals(resource))return new HexuOperations(db).audit(a,shop);if("pointRiskCases".equals(resource))return db.jdbc().queryForList("select r.*,m.name from hx_point_risk_case r join hx_member m on m.id=r.member_id where r.shop_id=? order by r.created_at desc limit 500",shop);if("pointTransfers".equals(resource))return new HexuPointRisk(db).transfers(a,shop);if("pointTransferSummary".equals(resource))return Collections.singletonList(new HexuPointRisk(db).transferSummary(a,shop));if("agentCounts".equals(resource))return db.jdbc().queryForList("select rank_no,count(*) total from hx_agent where shop_id=? and status='ACTIVE' group by rank_no",shop);if("earningSummary".equals(resource))return Collections.singletonList(new HexuReadModels(db).earningSummary(a,shop));if("agents".equals(resource))return new HexuReadModels(db).agents(a,shop);if("customers".equals(resource))return new HexuReadModels(db).customers(a,shop);if("financeSummary".equals(resource))return Collections.singletonList(new HexuReconciliation(db).summary(a,shop));if(resource.startsWith("reconciliation")){String table="reconciliationBatches".equals(resource)?"hx_reconciliation_batch":"reconciliationLines".equals(resource)?"hx_reconciliation_line":"reconciliationAdjustments".equals(resource)?"hx_reconciliation_adjustment":"";require(!table.isEmpty(),"未知对账资源");return db.jdbc().queryForList("select t.* from "+table+" t "+("reconciliationLines".equals(resource)?"join hx_reconciliation_batch b on b.id=t.batch_id ":"")+" where t.shop_id=? order by "+("reconciliationLines".equals(resource)?"b.created_at desc,t.row_no":"t.created_at desc")+" limit 500",shop);}if("shopReadiness".equals(resource))return Collections.singletonList(new HexuShopLifecycle(db).readiness(shop));if("assessmentPeriods".equals(resource))return db.jdbc().queryForList("select * from hx_assessment_period where shop_id=? order by period_end desc,agent_id limit 500",shop);if("groups".equals(resource))return db.jdbc().queryForList("select * from hx_group_buy where shop_id=? order by created_at desc limit 500",shop);if("groupRefunds".equals(resource))return db.jdbc().queryForList("select t.*,r.shop_id,r.order_id,r.amount,r.status refund_status from hx_group_refund t join hx_refund r on r.id=t.refund_id where r.shop_id=? order by t.updated_at desc limit 500",shop);Map<String,String> tables=new HashMap<>();tables.put("linkParticipants","hx_link_participant");tables.put("linkOrders","hx_link_order");tables.put("linkRewards","hx_link_reward");tables.put("reconciliationBatches","hx_reconciliation_batch");tables.put("reconciliationLines","hx_reconciliation_line");tables.put("reconciliationAdjustments","hx_reconciliation_adjustment");tables.put("pointBatches","hx_point_batch");tables.put("channelEvents","hx_channel_event");tables.put("agents","hx_agent");tables.put("customers","hx_binding");tables.put("inventory","hx_inventory_log");tables.put("earnings","hx_earning");tables.put("withdrawals","hx_withdrawal");tables.put("refunds","hx_refund");tables.put("ledger","hx_ledger");tables.put("points","hx_points");tables.put("pointLedger","hx_point_log");tables.put("audit","hx_audit");require(tables.containsKey(resource),"未知查询类型");if("points".equals(resource)){List<Map<String,Object>>list=new ArrayList<>();for(Map<String,Object>p:db.jdbc().queryForList("select member_id from hx_points where shop_id=? limit 500",shop))list.add(new HexuPointRisk(db).account(a,shop,number(p,"member_id")));return list;}return db.jdbc().queryForList("select * from "+tables.get(resource)+" where shop_id=? limit 500",shop);}
    private void applyBinding(Actor a,Map<String,Object>d,Map<String,Object>b){long shop=number(d,"shop_id"),member=number(d,"member_id"),target=number(b,"targetShopId"),agent=number(b,"targetAgentId");db.one("select id from hx_member where id=? for update",member);for(long id:new long[]{Math.min(shop,target),Math.max(shop,target)})db.one("select id from hx_shop where id=? for update",id);db.shop(target);db.one("select id from hx_agent where id=? and shop_id=? and status='ACTIVE'",agent,target);Map<String,Object> old=db.one("select * from hx_binding where member_id=? and shop_id=?",member,shop);require(shop==target||db.optional("select * from hx_binding where member_id=? and shop_id=?",member,target)==null,"目标商城已有绑定，请复核");db.jdbc().update("update hx_binding set shop_id=?,agent_id=?,bound_at=CURRENT_TIMESTAMP where member_id=? and shop_id=?",target,agent,member,shop);db.audit(a,shop,"MIGRATION_EFFECTIVE",str(d,"id"),old,b);}
    @Transactional public void applyScheduledMigrations(){for(Map<String,Object>d:db.jdbc().queryForList("select * from hx_document where kind='migration' and status='SCHEDULED' order by created_at limit 100 for update")){Map<String,Object>b=db.parse(d.get("body_json"));if(java.sql.Timestamp.valueOf(str(b,"effectiveAt")).getTime()<=System.currentTimeMillis()){applyBinding(new Actor(number(d,"reviewer"),true,true),d,b);db.jdbc().update("update hx_document set status='APPLIED',updated_at=CURRENT_TIMESTAMP where id=?",d.get("id"));db.message(number(b,"targetShopId"),number(d,"member_id"),"客户归属迁移已生效",str(d,"id"));}}}


    private void applyPromotion(long member,long shop,Map<String,Object>b){db.one("select id from hx_shop where id=? for update",shop);Map<String,Object>agent=db.one("select * from hx_agent where member_id=? and shop_id=? and status='ACTIVE' for update",member,shop);require(number(agent,"rank_no")==number(b,"fromRank")&&number(b,"rank")==number(b,"fromRank")+1&&number(b,"rank")<=3,"晋升职级已变化");if(number(agent,"parent_id")>0){Map<String,Object>parent=db.one("select rank_no from hx_agent where id=? and status='ACTIVE'",agent.get("parent_id"));require(number(parent,"rank_no")>=number(b,"rank"),"新职级高于直接上级，请先审核调整代理关系");}db.jdbc().update("update hx_agent set rank_no=? where id=?",number(b,"rank"),agent.get("id"));}
    @Transactional public void applyScheduledPromotions(){for(Map<String,Object>d:db.jdbc().queryForList("select * from hx_document where kind='promotion' and status='SCHEDULED' order by created_at limit 100 for update")){Map<String,Object>b=db.parse(d.get("body_json"));if(number(b,"effectiveAt")<=System.currentTimeMillis()){applyPromotion(number(d,"member_id"),number(d,"shop_id"),b);db.jdbc().update("update hx_document set status='APPLIED' where id=?",d.get("id"));db.audit(new Actor(number(d,"reviewer"),true,true),number(d,"shop_id"),"PROMOTION_EFFECTIVE",str(d,"id"),b.get("fromRank"),b.get("rank"));db.message(number(d,"shop_id"),number(d,"member_id"),"新代理职级已生效",str(d,"id"));}}}

    @Transactional public Object runDowngrades(Actor a,long shop){db.manage(a,shop,"OWNER");return map("periodsCreated",new HexuDowngrades(db).run(shop,java.time.LocalDate.now(java.time.ZoneOffset.UTC)));}
    public List<Map<String,Object>> downgradeShops(){return db.jdbc().queryForList("select distinct shop_id from hx_document where kind='downgrade_rule' and status='ACTIVE'");}
    public List<Map<String,Object>> dueDowngrades(){return db.jdbc().queryForList("select id from hx_document where kind='downgrade' and status='SCHEDULED' order by id limit 100");}
    @Transactional public void applyDowngrade(String id){Map<String,Object>d=db.one("select * from hx_document where id=? and kind='downgrade' for update",id);if(!"SCHEDULED".equals(str(d,"status")))return;Map<String,Object>b=db.parse(d.get("body_json"));if(number(b,"effectiveAt")>System.currentTimeMillis())return;new HexuDowngrades(db).reviewEvidence(number(d,"member_id"),number(d,"shop_id"),b);new HexuDowngrades(db).apply(number(d,"member_id"),number(d,"shop_id"),b);db.jdbc().update("update hx_document set status='APPLIED',updated_at=CURRENT_TIMESTAMP where id=?",id);}

    public List<Map<String,Object>> dueShopTransfers(){return db.jdbc().queryForList("select id from hx_document where kind='shop_transfer' and status='SCHEDULED' order by id limit 100");}
    @Transactional public void applyShopTransfer(String id){Map<String,Object>d=db.one("select * from hx_document where id=? and kind='shop_transfer' for update",id);if(!"SCHEDULED".equals(str(d,"status")))return;Map<String,Object>b=db.parse(d.get("body_json"));if(number(b,"effectiveTimestamp")>System.currentTimeMillis())return;new HexuShopLifecycle(db).apply(new Actor(number(d,"reviewer"),true,true),number(d,"shop_id"),b);db.jdbc().update("update hx_document set status='APPLIED',updated_at=CURRENT_TIMESTAMP where id=?",id);}

    @Transactional public void deferScheduledReview(String id,String reason){Map<String,Object>d=db.one("select * from hx_document where id=? for update",id);if(!"SCHEDULED".equals(str(d,"status")))return;String note=reason==null?"生效条件发生变化，请复核":reason.substring(0,Math.min(500,reason.length()));db.jdbc().update("update hx_document set status='REVIEW_REQUIRED',review_note=?,updated_at=CURRENT_TIMESTAMP where id=?",note,id);db.audit(new Actor(0,true,true),number(d,"shop_id"),"SCHEDULE_REVIEW_REQUIRED",id,d,map("reason",note));db.message(number(d,"shop_id"),number(d,"member_id"),"预约变更未生效，请复核处置条件",id);}

    @Transactional public Object importStatement(Actor actor,long shop,Map<String,Object>b){return new HexuReconciliation(db).submit(actor,shop,b);}
    public List<Map<String,Object>> reconciliationQueue(){return db.jdbc().queryForList("select id from hx_reconciliation_batch where status in ('QUEUED','PROCESSING') order by created_at limit 20");}
    @Transactional public void processStatement(String id){new HexuReconciliation(db).process(id);}
    @Transactional public Object recheckStatement(Actor actor,String id){return new HexuReconciliation(db).recheck(actor,id);}
    @Transactional public Object proposeAdjustment(Actor actor,String id,Map<String,Object>b){return new HexuReconciliation(db).propose(actor,id,b);}
    @Transactional public Object reviewAdjustment(Actor actor,String id,Map<String,Object>b){return new HexuReconciliation(db).review(actor,id,b);}
    @Transactional public Map<String,Object> assignStaff(Actor a,long shop,Map<String,Object>b){return new HexuOperations(db).assign(a,shop,b);}
    @Transactional public Map<String,Object> readMessage(Actor a,long id){return new HexuNotifications(db).read(a,id);}
}
