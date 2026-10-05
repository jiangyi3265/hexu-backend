package com.ruoyi.hexu;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Service
public class HexuOrderService {
    private static final Logger LOG=LoggerFactory.getLogger(HexuOrderService.class);
    private final HexuStore db; private final HexuCatalogService catalog;
    private static final String PURCHASE_AGREEMENT="PURCHASE_AGREEMENT";
    private static final Map<String,Object> PUBLIC_UI_ASSETS=Collections.unmodifiableMap(new HexuUiConfigService(new com.fasterxml.jackson.databind.ObjectMapper()).assets(null));
    @Autowired(required=false) private HexuMerchantOnboarding onboarding;
    @Autowired(required=false) private HexuPolicies policies;
    @Value("${hexu.wechat-pay.enabled:false}") private boolean realPaymentEnabled;
    @Value("${hexu.fulfillment.require-pick:true}") private boolean requirePick;
    public HexuOrderService(HexuStore db,HexuCatalogService catalog){this.db=db;this.catalog=catalog;}
    /** Use the order-time presentation fields without making unpublished images public. */
    private void publicLineFields(String orderId,long shop,List<Map<String,Object>> lines,Map<String,Map<String,Object>> cache){
        Map<String,String> verifiedAssets=new HashMap<>();
        Map<String,Boolean> historicalFiles=new HashMap<>();
        for(Map<String,Object> line:lines){
            Map<String,Object> snapshot=db.parse(line.get("snapshot_json"));
            boolean savedSpec=snapshot.get("spec") instanceof String,savedAsset=snapshot.get("asset") instanceof String;
            Map<String,Object> fallback=null;
            if(!savedSpec||!savedAsset){String sku=str(line,"sku_id"),key=shop+":"+sku;fallback=cache.get(key);
                if(fallback==null){Map<String,Object> product=db.optional("select spec,asset from hx_sku where shop_id=? and id=?",shop,sku);fallback=product==null?map("spec","","asset",""):map("spec",str(product,"spec"),"asset",publicProductAsset(shop,str(product,"asset")));cache.put(key,fallback);}
            }
            line.put("spec",savedSpec?snapshot.get("spec"):fallback.get("spec"));
            if(savedAsset){
                String raw=(String)snapshot.get("asset"),published=verifiedAssets.computeIfAbsent(raw,value->publicProductAsset(shop,value));
                line.put("asset",published.isEmpty()?privateOrderAsset(orderId,shop,line,raw,historicalFiles):published);
            }else line.put("asset",fallback.get("asset"));
        }
    }
    private String privateOrderAsset(String orderId,long shop,Map<String,Object> line,String raw,Map<String,Boolean> cache){
        String prefix="/hexu/app/attachments/product/";
        if(!raw.matches("/hexu/app/attachments/product/FILE[0-9a-f]{32}"))return "";
        String file=raw.substring(prefix.length());
        boolean exists=cache.computeIfAbsent(file,id->db.optional("select id from hx_attachment where id=? and shop_id=? and purpose='PRODUCT'",id,shop)!=null);
        return exists?"/hexu/app/attachments/order-cover/"+orderId+"/"+line.get("id"):"";
    }
    private String publicProductAsset(long shop,String raw){
        // Canonical paths cannot be remapped by the shopper's current storefront asset aliases.
        if(PUBLIC_UI_ASSETS.containsKey(raw))return String.valueOf(PUBLIC_UI_ASSETS.get(raw));
        for(Map.Entry<String,Object> asset:PUBLIC_UI_ASSETS.entrySet())if(raw.equals("/hexu/app/ui-assets/"+asset.getKey())||raw.equals(asset.getValue()))return String.valueOf(asset.getValue());
        String prefix="/hexu/app/attachments/product/",file=raw.startsWith(prefix)?raw.substring(prefix.length()):raw;
        if(!file.matches("FILE[0-9a-f]{32}")||db.optional("select id from hx_attachment where id=? and shop_id=? and purpose='PRODUCT'",file,shop)==null)return "";
        // Match publishedProduct's existing PRODUCT / active shop / published SKU or gallery gate.
        for(Map<String,Object> product:db.jdbc().queryForList("select k.id,k.asset from hx_sku k join hx_shop s on s.id=k.shop_id where k.shop_id=? and k.status='ACTIVE' and s.status='ACTIVE'",shop)){
            if(file.equals(str(product,"asset")))return prefix+file;
            Object gallery=new HexuProductData(db).detail(shop,str(product,"id")).get("gallery");
            if(gallery instanceof List&&((List<?>)gallery).contains(file))return prefix+file;
        }
        return "";
    }
    @Transactional public Map<String,Object> redeem(Actor a,long shop,Map<String,Object>b){
        db.member(a);db.one("select id from hx_shop where id=? for update",shop);db.shop(shop);require(bool(catalog.access(a,shop),"allowed"),"请在所属商城兑换");catalog.bind(a,shop,"");
        long scope=number(b,"scope"),qty=number(b,"qty");require(scope==0||scope==shop,"商城积分不能跨店使用");require(qty>0&&qty<=1000,"兑换数量不合法");Map<String,Object>sku=db.one("select * from hx_sku where shop_id=? and id=? and status='ACTIVE' for update",shop,str(b,"skuId"));long cost=Math.multiplyExact(number(sku,"point_price"),qty);require(cost>0,"该商品尚未开放积分兑换");
        require(b.get("address") instanceof Map,"请选择收货地址");Map<String,Object> address=(Map<String,Object>)b.get("address");require(!str(address,"name").isEmpty()&&!str(address,"detail").isEmpty(),"收货地址不完整");String ref=id("DH");long rule=db.jdbc().queryForObject("select max(id) from hx_rule",Long.class);
        db.points(scope,a.id,-cost,"REDEMPTION",ref,false);db.inventory(shop,str(sku,"id"),-Math.toIntExact(qty),0,"POINTS_REDEEM",ref);
        db.jdbc().update("insert into hx_order(id,shop_id,buyer_id,order_type,status,subtotal,total,points_used,points_scope,merchant_no,rule_id,address_json,expires_at) values(?,?,?,'POINTS','PAID',0,0,?,?,'POINTS',?,?,CURRENT_TIMESTAMP)",ref,shop,a.id,cost,scope,rule,db.json(address));
        db.jdbc().update("insert into hx_order_line(order_id,sku_id,name,qty,unit_price,paid,snapshot_json) values(?,?,?,?,0,0,?)",ref,str(sku,"id"),str(sku,"name"),qty,db.json(map("spec",str(sku,"spec"),"asset",publicProductAsset(shop,str(sku,"asset")),"pointPrice",sku.get("point_price"),"pointsScope",scope,"pointsRate",0,"autoReceiveDays",db.parameter(shop,"system_parameter","autoReceiveDays",7),"afterSalesDays",db.parameter(shop,"system_parameter","afterSalesDays",7))));
        db.audit(a,shop,"POINTS_REDEMPTION",ref,null,map("cost",cost,"scope",scope));db.message(shop,a.id,"积分兑换成功，等待发货",ref);return detail(a,ref,false);
    }
    @Transactional(readOnly=true) public Map<String,Object> simulate(Actor a,long shop,Map<String,Object>b){
        db.manage(a,shop,"FINANCE");Map<String,Object>sku=db.one("select * from hx_sku where shop_id=? and id=?",shop,str(b,"skuId"));int qty=Math.toIntExact(number(b,"qty"));require(qty>0&&qty<=100000,"试算数量须为1至100000");Map<String,Object>owner=db.agent(number(db.shop(shop),"owner_id"),shop);List<SettlementCalculator.Node>chain=db.chain(number(b,"agentId"),shop);Map<String,Object>rule=db.one("select * from hx_rule where effective_at<=CURRENT_TIMESTAMP order by effective_at desc,id desc limit 1");long[]prices={number(sku,"retail"),number(sku,"cloud_price"),number(sku,"center_price"),number(sku,"owner_price")};int[]bps={0,(int)number(rule,"cloud_bps"),(int)number(rule,"center_bps"),(int)number(rule,"owner_bps")};if(b.containsKey("cloudBps")){require(number(b,"cloudBps")>=0&&number(b,"cloudBps")<=10000,"试算分红率超出范围");bps[1]=(int)number(b,"cloudBps");}long paid=b.containsKey("paid")?number(b,"paid"):Math.multiplyExact(prices[0],qty);require(paid>=0&&paid<=Math.multiplyExact(prices[0],qty),"实付不能超出商品零售总额");List<Map<String,Object>>entries=new ArrayList<>();for(SettlementCalculator.Entry e:SettlementCalculator.calculate(paid,qty,prices,bps,chain,new SettlementCalculator.Node(number(owner,"id"),number(owner,"member_id"),(int)number(owner,"rank_no")),true)){Map<String,Object>m=db.one("select m.name from hx_agent a join hx_member m on m.id=a.member_id where a.id=?",e.agentId);entries.add(map("agentId",e.agentId,"name",m.get("name"),"type",e.type,"amount",e.amount));}return map("items",entries,"chain",chain,"paid",paid,"ownerId",owner.get("id"),"ruleId",rule.get("id"));
    }
    @Transactional(readOnly=true) public Map<String,Object> marketingPreview(Actor a,long shop,Map<String,Object>b){db.manage(a,shop,"FINANCE","OPERATOR");long buyer=number(b,"customerId");require(db.optional("select member_id from hx_binding where shop_id=? and member_id=?",shop,buyer)!=null||db.agent(buyer,shop)!=null,"试算客户须属于本商城");Map<String,Object>q=quote(new Actor(buyer,false,false),shop,b);Map<String,Object>owner=db.agent(number(db.shop(shop),"owner_id"),shop);long cost=0;String key=number(owner,"rank_no")==3?"owner_price":number(owner,"rank_no")==2?"center_price":"cloud_price";for(Map<String,Object>item:(List<Map<String,Object>>)b.get("items")){Map<String,Object>sku=db.one("select * from hx_sku where shop_id=? and id=?",shop,str(item,"id"));cost=Math.addExact(cost,Math.multiplyExact(number(sku,key),number(item,"qty")));}q.put("cost",cost);q.put("margin",number(q,"productPaid")-cost);q.put("validated",true);q.put("customerId",buyer);return q;}
    public List<Map<String,Object>> dueReceipts(){List<Map<String,Object>> due=new ArrayList<>();for(Map<String,Object>o:db.jdbc().queryForList("select * from hx_order where status='SHIPPED' order by created_at limit 500")){Map<String,Object>s=db.parse(o.get("shipping_json"));long at=number(s,"shippedAt"),days=number(s,"autoReceiveDays");if(at>0&&days>0&&at+days*86400000L<=System.currentTimeMillis())due.add(o);}return due;}
     @Transactional(readOnly=true) public Map<String,Object> wholesaleRules(Actor a,long shop,long destination){
         db.member(a);Map<String,Object> source=db.shop(shop),target=db.shop(destination);
         require("WHOLESALE".equals(str(source,"kind"))&&number(target,"owner_id")==a.id,"只能读取本人商城的公司批发规则");
         Map<String,Object> agent=db.agent(a.id,destination);
         require(agent!=null&&number(agent,"rank_no")>=2,"不具备批发采购资格");
         Map<String,Object> settings=source.get("settings")==null?Collections.emptyMap():db.parse(source.get("settings"));
         return map("mixedEnabled",bool(settings,"mixedEnabled"),"mixCapacity",number(settings,"mixCapacity"),"minMixBoxes",number(settings,"minMixBoxes"));
     }
     @SuppressWarnings("unchecked") @Transactional
    public Map<String,Object> create(Actor a,long shop,Map<String,Object> body){
        String remark=buyerRemark(body);
        db.member(a);db.one("select id from hx_shop where id=? for update",shop);Map<String,Object> s=db.shop(shop);require(bool(s,"payment_ready")&&!str(s,"merchant_no").isEmpty(),"商户进件或AppID授权未完成，不可在线支付");if(realPaymentEnabled)onboarding.requireReady(shop,str(s,"merchant_no"));
        boolean wholesale="WHOLESALE".equals(str(s,"kind"));
        // 零售下单须核对用户确认的当前商城协议；批发采购沿用独立的采购流程。
        Map<String,Object> purchaseAgreement=wholesale?Collections.emptyMap():verifiedPurchaseAgreement(shop,body);
        Object address=body.get("address");require(address instanceof Map&&!str((Map<String,Object>)address,"name").isEmpty()&&!str((Map<String,Object>)address,"detail").isEmpty(),"收货人和详细地址不能为空");
        require(body.get("items") instanceof List,"请选择商品");List<Map<String,Object>> input=(List<Map<String,Object>>)body.get("items");require(!input.isEmpty()&&input.size()<=100,"商品数量不合法");
        Map<String,Integer> merged=new TreeMap<>();for(Map<String,Object> line:input){String sku=str(line,"id");int qty=Math.toIntExact(number(line,"qty"));require(qty>0&&qty<=100000,"购买数量必须为1至100000的整数");merged.put(sku,Math.addExact(merged.getOrDefault(sku,0),qty));}
        Map<String,Object> ag=db.agent(a.id,shop);long destination=number(body,"destinationShopId");String type;
        if(wholesale){Map<String,Object> dest=db.shop(destination);require(number(dest,"owner_id")==a.id,"只能为本人经营的商城采购");ag=db.agent(a.id,destination);require(ag!=null&&number(ag,"rank_no")>=2,"不具备批发采购资格");type=bool(body,"directShip")?"DIRECT_SHIP":"WHOLESALE";}
        else{require(bool(catalog.access(a,shop),"allowed"),"您已有所属商城，请返回原商城或申请跨店采购审批");if(ag!=null||catalog.rank(a,shop)==0)catalog.bind(a,shop,str(body,"invite"));type=ag==null?("DIRECT".equals(str(s,"kind"))?"DIRECT_RETAIL":"DEALER_RETAIL"):"AGENT_PURCHASE";}
        Map<String,Object> rule=db.one("select * from hx_rule where effective_at<=CURRENT_TIMESTAMP order by effective_at desc,id desc limit 1");
        long[] prices=new long[4];int[] bps={0,(int)number(rule,"cloud_bps"),(int)number(rule,"center_bps"),(int)number(rule,"owner_bps")};int rank=wholesale?(int)number(ag,"rank_no"):catalog.rank(a,shop);
        Map<String,Object>authorization=null;if(!wholesale&&ag==null&&rank>0){authorization=new HexuCrossPurchase(db).find(a,shop,merged,true);require(authorization!=null,"商品或累计数量超出跨店授权范围");rank=(int)number(authorization,"authorizedRank");}
        Map<String,Object> binding=wholesale?null:authorization!=null?map("agent_id",db.agent(number(s,"owner_id"),shop).get("id")):db.one("select * from hx_binding where member_id=? and shop_id=?",a.id,shop);
        List<SettlementCalculator.Node> chain=wholesale?Collections.emptyList():db.chain(ag==null?number(binding,"agent_id"):number(ag,"id"),shop);
        Map<String,Object> owner=wholesale?null:db.agent(number(s,"owner_id"),shop);require(wholesale||owner!=null,"商城拥有者配置不完整");
        Map<String,Object> settings=s.get("settings")==null?new HashMap<>():db.parse(s.get("settings"));boolean mixed=wholesale&&bool(settings,"mixedEnabled");long capacity=number(settings,"mixCapacity"),minBoxes=number(settings,"minMixBoxes");Map<String,Long> mixedUnits=new HashMap<>();
        List<Map<String,Object>> lines=new ArrayList<>();long subtotal=0;
        for(Map.Entry<String,Integer> e:merged.entrySet()){
            Map<String,Object> sku=db.one("select * from hx_sku where shop_id=? and id=? and status='ACTIVE' for update",shop,e.getKey());int qty=e.getValue();
            require(qty>=number(sku,"min_qty"),"未达到商品最小起订量");require(qty<=number(sku,"available"),"商品库存不足："+str(sku,"name"));
            if(!wholesale)new HexuProductData(db).retailQuantity(shop,sku,qty);
            if(wholesale){if(mixed&&!str(sku,"mix_group").isEmpty()){require(capacity>0&&minBoxes>0,"混批箱容和最低箱数未配置");String group=str(sku,"mix_group");mixedUnits.put(group,Math.addExact(mixedUnits.getOrDefault(group,0L),Math.multiplyExact(qty,number(sku,"mix_units"))));}
                else require(qty%number(sku,"box_size")==0&&qty>=number(sku,"box_size")*number(sku,"min_boxes"),"采购须满足整箱倍数及最低箱数");}
            long unit=number(sku,rank==0?"retail":rank==1?"cloud_price":rank==2?"center_price":"owner_price");long amount=Math.multiplyExact(unit,qty);subtotal=Math.addExact(subtotal,amount);lines.add(map("sku",sku,"qty",qty,"unit",unit,"amount",amount));
        }
        for(long units:mixedUnits.values())require(units%capacity==0&&units>=capacity*minBoxes,"混批组不满足整箱箱容或最低箱数");
        Map<String,Object> groupPlan=new HexuGroupPurchase(db).plan(a,shop,body,true);require(groupPlan.isEmpty()||!wholesale&&rank==0,"拼团仅适用本商城普通零售订单");
        Map<String,Object> pricing=new HexuPricing(db).calculate(a,shop,lines,body,wholesale,true);
        long discount=number(pricing,"discount"),points=number(pricing,"pointsUsed"),freight=number(pricing,"freight"),productPaid=number(pricing,"productPaid");String couponId=str(body,"couponId");Map<String,Object>coupon=(Map<String,Object>)pricing.get("coupon");String order=id("HX");if(!wholesale&&rank>0)type="AGENT_PURCHASE";
         if(body.containsKey("expectedTotal"))require(number(body,"expectedTotal")==productPaid+freight,"订单金额已变化，请刷新报价后再次确认");
        db.jdbc().update("insert into hx_order(id,shop_id,buyer_id,destination_shop_id,order_type,status,subtotal,discount,freight,total,points_used,merchant_no,rule_id,address_json,remark,expires_at) values(?,?,?,?,?,'UNPAID',?,?,?,?,?,?,?,?,?,?)",order,shop,a.id,wholesale?destination:null,type,subtotal,discount,freight,productPaid+freight,points,str(s,"merchant_no"),number(rule,"id"),db.json(address),remark,Timestamp.from(Instant.now().plusSeconds(db.parameter(shop,"system_parameter","unpaidMinutes",15)*60)));
        db.jdbc().update("update hx_order set points_scope=?,cross_authorization_id=? where id=?",number(pricing,"pointsScope"),authorization==null?null:authorization.get("id"),order);
        for(Map<String,Object>promotion:(List<Map<String,Object>>)pricing.get("promotions"))db.jdbc().update("insert into hx_marketing_usage(order_id,campaign_id,qty) values(?,?,?)",order,promotion.get("id"),promotion.get("qty"));
        Map<String,Object>linkPlan=!wholesale?new HexuLinkCampaign(db).capture(a.id,shop,number(owner,"id")):Collections.emptyMap();
        Map<String,Object>invitationPlan=!wholesale&&ag==null&&rank==0?new HexuInvitationRewards(db).capture(a.id,shop,number(owner,"id")):Collections.emptyMap();
        int pricedIndex=0;
        for(Map<String,Object> l:lines){Map<String,Object> sku=(Map<String,Object>)l.get("sku");int qty=(int)number(l,"qty");long paid=((long[])pricing.get("paidLines"))[pricedIndex++];
            prices=new long[]{number(sku,"retail"),number(sku,"cloud_price"),number(sku,"center_price"),number(sku,"owner_price")};
             Map<String,Object> snapshot=map("spec",str(sku,"spec"),"asset",publicProductAsset(shop,str(sku,"asset")),"buyerAgentId",ag==null?0:ag.get("id"),"linkCampaign",linkPlan,"group",groupPlan,"invitation",invitationPlan,"prices",prices,"bps",bps,"rank",rank,"promotions",pricing.get("promotions"),"pointsScope",pricing.get("pointsScope"),"pointsPerYuan",pricing.get("pointsPerYuan"),"ruleId",rule.get("id"),"chain",chain,"boxSize",sku.get("box_size"),"minBoxes",sku.get("min_boxes"),"merchant",s.get("merchant_no"),"pointsExpiryDays",db.parameter(0,"points_rule","expiryDays",365),"pointsRate",db.parameter(0,"points_rule","purchaseRate",1),"shopPointsRate",number(db.config(shop,"points_rule"),"purchaseRate"),"shopPointsExpiryDays",db.parameter(shop,"points_rule","expiryDays",365),"reviewPoints",db.parameter(0,"points_rule","reviewPoints",10),"shopReviewPoints",number(db.config(shop,"points_rule"),"reviewPoints"),"afterSalesDays",db.parameter(shop,"system_parameter","afterSalesDays",7));
            long lineId=db.insert("insert into hx_order_line(order_id,sku_id,name,qty,unit_price,paid,snapshot_json) values(?,?,?,?,?,?,?)",order,str(sku,"id"),str(sku,"name"),qty,number(l,"unit"),paid,db.json(snapshot));
            if(!wholesale&&ag==null&&rank>0){long margin=paid-Math.multiplyExact(prices[(int)number(owner,"rank_no")],qty);require(margin>=0,"特批采购价不足以承担成本");db.jdbc().update("insert into hx_earning(order_id,line_id,shop_id,agent_id,earning_type,amount) values(?,?,?,?,?,?)",order,lineId,shop,number(owner,"id"),"LEVEL",margin);}else if(!wholesale){SettlementCalculator.Node ownerNode=new SettlementCalculator.Node(number(owner,"id"),number(owner,"member_id"),(int)number(owner,"rank_no"));for(SettlementCalculator.Entry earning:SettlementCalculator.calculate(paid,qty,prices,bps,chain,ownerNode,rank==0))db.jdbc().update("insert into hx_earning(order_id,line_id,shop_id,agent_id,earning_type,amount) values(?,?,?,?,?,?)",order,lineId,shop,earning.agentId,earning.type,earning.amount);}
            db.inventory(shop,str(sku,"id"),-qty,qty,"ORDER_LOCK",order);
        }
        if(points>0)db.points(number(pricing,"pointsScope"),a.id,-points,"ORDER_USE",order,false);
        if(coupon!=null)db.jdbc().update("update hx_document set status='LOCKED',review_note=? where id=?",order,couponId);

        new HexuGroupPurchase(db).attach(a,shop,order,groupPlan);
        if(!wholesale){
            String version=str(purchaseAgreement,"version");
            if(db.optional("select member_id from hx_policy_consent where member_id=? and shop_id=? and policy_type=? and version=?",a.id,shop,PURCHASE_AGREEMENT,version)==null)
                db.jdbc().update("insert into hx_policy_consent(member_id,shop_id,policy_type,version,source) values(?,?,?,?,?)",a.id,shop,PURCHASE_AGREEMENT,version,Boolean.TRUE.equals(purchaseAgreement.get("sandbox"))?"ORDER_CREATE_SANDBOX":"ORDER_CREATE");
        }
        db.audit(a,shop,"ORDER_CREATE",order,null,map("type",type,"total",productPaid+freight,"agreementPolicyId",purchaseAgreement.get("id"),"agreementVersion",purchaseAgreement.get("version")));return detail(a,order,false);
    }
    private Map<String,Object> verifiedPurchaseAgreement(long shop,Map<String,Object> body){
        require(Boolean.TRUE.equals(body.get("orderConsent")),"请确认购买协议");
        Object current=(policies==null?new HexuPolicies(db):policies).current(shop).get(PURCHASE_AGREEMENT);
        require(current instanceof Map,"购买协议正文尚未发布");
        Map<String,Object> agreement=(Map<String,Object>)current;
        require(!str(body,"agreementPolicyId").isEmpty()
                &&str(body,"agreementPolicyId").equals(str(agreement,"id"))
                &&str(body,"agreementVersion").equals(str(agreement,"version")),"购买协议已更新，请阅读最新版本后重新确认");
        return agreement;
    }
    @Transactional(readOnly=true) public Map<String,Object> quote(Actor a,long shop,Map<String,Object>b){
        db.member(a);Map<String,Object>s=db.shop(shop);require(!"WHOLESALE".equals(str(s,"kind")),"批发订单请使用采购结算");require(bool(catalog.access(a,shop),"allowed"),"请返回所属商城");require(b.get("items") instanceof List,"请选择商品");List<?>input=(List<?>)b.get("items");require(!input.isEmpty()&&input.size()<=100,"商品数量不合法");Map<String,Long>merged=new TreeMap<>();for(Object obj:input){Map<String,Object>line=(Map<String,Object>)obj;long qty=number(line,"qty");require(qty>0&&qty<=100000,"商品数量不合法");String sku=str(line,"id");merged.put(sku,Math.addExact(merged.getOrDefault(sku,0L),qty));}
        int rank=catalog.rank(a,shop);if(db.agent(a.id,shop)==null&&rank>0){Map<String,Object>d=new HexuCrossPurchase(db).find(a,shop,merged,false);require(d!=null,"商品或累计数量超出跨店授权范围");rank=(int)number(d,"authorizedRank");}List<Map<String,Object>>lines=new ArrayList<>();for(Map.Entry<String,Long>entry:merged.entrySet()){Map<String,Object>sku=db.one("select * from hx_sku where shop_id=? and id=? and status='ACTIVE'",shop,entry.getKey());require(entry.getValue()>=number(sku,"min_qty"),"未达到商品最小起订量");require(entry.getValue()<=number(sku,"available"),"商品库存不足");new HexuProductData(db).retailQuantity(shop,sku,entry.getValue());long unit=number(sku,rank==0?"retail":rank==1?"cloud_price":rank==2?"center_price":"owner_price");lines.add(map("sku",sku,"qty",entry.getValue(),"unit",unit,"amount",Math.multiplyExact(unit,entry.getValue())));}
        require((str(b,"groupId").isEmpty()&&str(b,"groupCampaignId").isEmpty())||rank==0,"拼团仅适用普通零售订单");Map<String,Object>quote=new HexuPricing(db).calculate(a,shop,lines,b,false,false);verifyQuote(a,shop,rank,lines,(long[])quote.get("paidLines"));
        boolean couponStackable=str(b,"groupId").isEmpty()&&str(b,"groupCampaignId").isEmpty();
        for(Map<String,Object>promotion:(List<Map<String,Object>>)quote.get("promotions")){
            couponStackable&=bool((Map<String,Object>)promotion.get("body"),"stackCoupon");
        }
        quote.put("couponStackable",couponStackable);
        quote.remove("coupon");quote.remove("paidLines");quote.remove("promotions");return quote;
    }
    private static String buyerRemark(Map<String,Object> body){
        if(!body.containsKey("remark"))return "";
        require(body.get("remark") instanceof String,"买家留言须为文本");
        String remark=(String)body.get("remark");require(remark.codePointCount(0,remark.length())<=200&&!remark.contains("\u0000"),"买家留言最多200个字符");return remark;
    }
    private void verifyQuote(Actor a,long shop,int rank,List<Map<String,Object>>lines,long[] paid){
        Map<String,Object>s=db.shop(shop),owner=db.agent(number(s,"owner_id"),shop),agent=db.agent(a.id,shop);require(owner!=null,"商城拥有者配置不完整");Map<String,Object>binding=db.optional("select * from hx_binding where member_id=? and shop_id=?",a.id,shop);long start=agent!=null?number(agent,"id"):binding==null?number(owner,"id"):number(binding,"agent_id");List<SettlementCalculator.Node>chain=db.chain(start,shop);Map<String,Object>rule=db.one("select * from hx_rule where effective_at<=CURRENT_TIMESTAMP order by effective_at desc,id desc limit 1");int[]bps={0,(int)number(rule,"cloud_bps"),(int)number(rule,"center_bps"),(int)number(rule,"owner_bps")};
        for(int i=0;i<lines.size();i++){Map<String,Object>sku=(Map<String,Object>)lines.get(i).get("sku");int qty=(int)number(lines.get(i),"qty");long[]prices={number(sku,"retail"),number(sku,"cloud_price"),number(sku,"center_price"),number(sku,"owner_price")};if(agent==null&&rank>0)require(paid[i]>=Math.multiplyExact(prices[(int)number(owner,"rank_no")],qty),"特批采购优惠后不足以承担成本");else SettlementCalculator.calculate(paid[i],qty,prices,bps,chain,new SettlementCalculator.Node(number(owner,"id"),number(owner,"member_id"),(int)number(owner,"rank_no")),rank==0);}
    }
    public Map<String,Object> detail(Actor a,String id,boolean management){
        Map<String,Object> order=management?db.one("select * from hx_order where id=?",id):db.optional("select * from hx_order where id=?",id);
        if(management)db.manage(a,number(order,"shop_id"),"ORDER","WAREHOUSE","FINANCE","SUPPORT");
        else if(order==null||a.staff||number(order,"buyer_id")!=a.id){
            LOG.info("Order detail denied for actor {} and order {}: {}",a.id,id,order==null?"missing":"not owner");
            throw new BusinessException("订单不存在或无权查看");
        }
        Map<String,Object> receipt=db.optional("select created_at from hx_ledger where reference_id=? and shop_id=? and category='RECEIPT' order by id limit 1",id,order.get("shop_id"));
        if(receipt!=null)order.put("paid_at",receipt.get("created_at"));
        if(management){
            Map<String,Object> buyer=db.optional("select name from hx_member where id=?",order.get("buyer_id"));
            if(buyer!=null)order.put("buyerName",buyer.get("name"));
            Map<String,Object> rule=db.optional("select effective_at from hx_rule where id=?",order.get("rule_id"));
            if(rule!=null&&rule.get("effective_at")!=null)order.put("ruleEffectiveAt",rule.get("effective_at").toString().substring(0,10));
        }
        List<Map<String,Object>> lines=db.jdbc().queryForList("select * from hx_order_line where order_id=? order by id",id);
        if(!lines.isEmpty()){
            Map<String,Object> snapshot=db.parse(lines.get(0).get("snapshot_json"));
            Object raw=snapshot.get("chain");
            if(raw instanceof List&&!((List<?>)raw).isEmpty()){
                Map<String,Object> node=(Map<String,Object>)((List<?>)raw).get(0);
                Map<String,Object> member=db.optional("select name from hx_member where id=?",node.get("memberId"));
                if(member!=null)order.put("customerAgentName",member.get("name"));
            }
        }
        publicLineFields(id,number(order,"shop_id"),lines,new HashMap<>());
        if(!management)for(Map<String,Object> line:lines)line.remove("snapshot_json");
        order.put("items",lines);
        Map<String,Object> group=db.optional("select g.id,g.status,g.expires_at from hx_group_buy g join hx_group_member m on m.group_id=g.id where m.order_id=?",id);
        if(group!=null)order.put("group",group);
        return order;
    }
    public List<Map<String,Object>> list(Actor a,long shop,boolean management){
        if(management)db.manage(a,shop,"ORDER","WAREHOUSE","FINANCE","SUPPORT");else db.member(a);
        List<Map<String,Object>> result=db.jdbc().queryForList("select * from hx_order where shop_id=?"+(management?"":" and buyer_id="+a.id)+" order by created_at desc limit 200",shop);
        Map<String,Map<String,Object>> presentation=new HashMap<>();
        for(Map<String,Object> order:result){List<Map<String,Object>> items=db.jdbc().queryForList("select id,sku_id,name,qty,unit_price,paid,refunded_qty,refunded_paid,snapshot_json from hx_order_line where order_id=? order by id",order.get("id"));publicLineFields(str(order,"id"),number(order,"shop_id"),items,presentation);for(Map<String,Object> line:items)line.remove("snapshot_json");order.put("items",items);Map<String,Object>group=db.optional("select g.id,g.status,g.expires_at from hx_group_buy g join hx_group_member m on m.group_id=g.id where m.order_id=?",order.get("id"));if(group!=null)order.put("group",group);}
        return result;
    }
    @Transactional public Object remind(Actor a,String id){Map<String,Object>o=detail(a,id,false);require("PAID".equals(str(o,"status")),"仅待发货订单可提醒");require(db.jdbc().queryForObject("select count(*) from hx_audit where action='SHIP_REMINDER' and reference_id=? and created_at>=?",Long.class,id,new java.sql.Timestamp(System.currentTimeMillis()-3600000))==0,"已提醒，请一小时后再试");Map<String,Object>s=db.shop(number(o,"shop_id"));db.message(number(o,"shop_id"),number(s,"owner_id"),"客户提醒发货",id);db.audit(a,number(o,"shop_id"),"SHIP_REMINDER",id,null,null);return map("sent",true);}
    @Transactional public Map<String,Object> payment(Actor a,String id){Map<String,Object> o=detail(a,id,false);require("UNPAID".equals(str(o,"status")),"订单不可支付");require(((Timestamp)o.get("expires_at")).toInstant().isAfter(Instant.now()),"订单支付已超时");if(realPaymentEnabled)onboarding.requireReady(number(o,"shop_id"),str(o,"merchant_no"));Map<String,Object> old=db.optional("select * from hx_payment where order_id=?",id);if(old!=null)return old;String payment=id("PAY");db.jdbc().update("insert into hx_payment(id,order_id,merchant_no,amount,status) values(?,?,?,?,'CREATED')",payment,id,str(o,"merchant_no"),number(o,"total"));return db.one("select * from hx_payment where id=?",payment);}
    @Transactional public Map<String,Object> paymentConfirmed(String payment,String merchant,long amount,String channelRef){Map<String,Object> paymentRow=db.one("select order_id from hx_payment where id=?",payment);new HexuGroupPurchase(db).lockForOrder(str(paymentRow,"order_id"));Map<String,Object> p=db.one("select * from hx_payment where id=? for update",payment);require(str(p,"merchant_no").equals(merchant)&&number(p,"amount")==amount,"支付商户或金额不匹配");if("SUCCESS".equals(str(p,"status"))){require(str(p,"channel_ref").equals(channelRef),"同一订单出现不同支付流水，请人工对账");return p;}new HexuGroupPurchase(db).paymentAllowed(str(p,"order_id"));Map<String,Object> o=db.one("select * from hx_order where id=? for update",p.get("order_id"));require("UNPAID".equals(str(o,"status")),"已关闭订单收到付款，需渠道退款对账");require(((Timestamp)o.get("expires_at")).toInstant().isAfter(Instant.now()),"超时订单付款需人工对账退款");
        long shop=number(o,"shop_id");for(Map<String,Object> line:db.jdbc().queryForList("select * from hx_order_line where order_id=?",o.get("id")))db.inventory(shop,str(line,"sku_id"),0,-(int)number(line,"qty"),"PAID",str(o,"id"));
        db.jdbc().update("update hx_payment set status='SUCCESS',channel_ref=? where id=?",channelRef,payment);db.jdbc().update("update hx_order set status='PAID' where id=?",o.get("id"));db.jdbc().update("update hx_document set status='USED' where kind='coupon_claim' and review_note=? and status='LOCKED'",o.get("id"));
        db.jdbc().update("insert into hx_ledger(shop_id,member_id,category,amount,reference_id) values(?,0,'RECEIPT',?,?)",shop,amount,o.get("id"));new HexuGroupPurchase(db).paid(str(o,"id"));db.message(shop,number(o,"buyer_id"),"订单支付成功",str(o,"id"));return db.one("select * from hx_payment where id=?",payment);
    }
    @Transactional public Map<String,Object> cancel(Actor a,String id){new HexuGroupPurchase(db).lockForOrder(id);Map<String,Object> o=db.one("select * from hx_order where id=? for update",id);require(number(o,"buyer_id")==a.id&&!a.staff||a.platform,"无权取消订单");if("CANCELLED".equals(str(o,"status")))return o;require("UNPAID".equals(str(o,"status")),"仅待付款订单可取消");cancelLocked(o);db.audit(a,number(o,"shop_id"),"ORDER_CANCEL",id,map("status","UNPAID"),map("status","CANCELLED"));return db.one("select * from hx_order where id=?",id);}
    void cancelLocked(Map<String,Object> o){String id=str(o,"id");db.jdbc().update("update hx_document set status='APPROVED',review_note=null where kind='cross_purchase' and status='USED' and review_note=?",id);long shop=number(o,"shop_id");for(Map<String,Object> l:db.jdbc().queryForList("select * from hx_order_line where order_id=?",id))db.inventory(shop,str(l,"sku_id"),(int)number(l,"qty"),-(int)number(l,"qty"),"CANCEL",id);if(number(o,"points_used")>0)db.points(number(o,"points_scope"),number(o,"buyer_id"),number(o,"points_used"),"CANCEL_RETURN",id,false);db.jdbc().update("update hx_order set status='CANCELLED' where id=?",id);db.jdbc().update("update hx_earning set status='CANCELLED' where order_id=?",id);db.jdbc().update("update hx_document set status='AVAILABLE',review_note=null where kind='coupon_claim' and status='LOCKED' and review_note=?",id);}
    @Transactional public int expire(){int count=0;for(Map<String,Object>candidate:db.jdbc().queryForList("select id from hx_order where status='UNPAID' and expires_at<CURRENT_TIMESTAMP order by id limit 100")){String id=str(candidate,"id");new HexuGroupPurchase(db).lockForOrder(id);Map<String,Object>o=db.one("select * from hx_order where id=? for update",id);if("UNPAID".equals(str(o,"status"))){cancelLocked(o);db.audit(new Actor(0,false,false),number(o,"shop_id"),"ORDER_EXPIRE",id,map("status","UNPAID"),map("status","CANCELLED"));count++;}}return count;}
    @Transactional public int expireGroups(){return new HexuGroupPurchase(db).expire(this);}
    @Transactional public Map<String,Object> ship(Actor a,String id,Map<String,Object> b){Map<String,Object> o=db.one("select * from hx_order where id=? for update",id);db.manage(a,number(o,"shop_id"),"WAREHOUSE","ORDER");new HexuGroupPurchase(db).fulfillmentAllowed(id);require("PAID".equals(str(o,"status")),"仅已付款未发货订单可发货");
        if(requirePick){Map<String,Object> pick=db.optional("select status from hx_pick_task where order_id=?",id);require(pick!=null&&"COMPLETED".equals(str(pick,"status")),"请先完成拣货再发货");}
        String carrier = str(b,"carrier").trim(), tracking = str(b,"tracking");
        require(b.get("carrier") instanceof String && !carrier.isEmpty() && carrier.codePointCount(0,carrier.length())<=60 && carrier.codePoints().noneMatch(ch->ch<0x20||ch>=0x7f&&ch<=0x9f) && b.get("tracking") instanceof String && tracking.matches("[A-Za-z0-9]{8,40}"),"请填写有效快递公司和8至40位字母数字运单号");
        require(db.jdbc().queryForObject("select count(*) from hx_refund where order_id=? and status not in ('REJECTED','SUCCESS','CLOSED')",Integer.class,id)==0,"售后处理中，暂不可发货");
        Map<String,Object> fulfillmentSnapshot=db.parse(db.one("select snapshot_json from hx_order_line where order_id=? order by id limit 1",id).get("snapshot_json"));
        db.jdbc().update("update hx_order set status='SHIPPED',shipping_json=? where id=?",db.json(map("carrier",carrier,"tracking",tracking,"shippedAt",System.currentTimeMillis(),"autoReceiveDays",fulfillmentSnapshot.containsKey("autoReceiveDays")?number(fulfillmentSnapshot,"autoReceiveDays"):7)),id);
        db.audit(a,number(o,"shop_id"),"SHIP",id,null,map("carrier",carrier,"tracking",tracking));db.message(number(o,"shop_id"),number(o,"buyer_id"),"订单已发货",id);return detail(a,id,true);}
    @Transactional public Map<String,Object> receive(Actor a,String id){Map<String,Object> o=db.one("select * from hx_order where id=? for update",id);require(!a.staff&&number(o,"buyer_id")==a.id,"无权确认收货");if("COMPLETED".equals(str(o,"status")))return detail(a,id,false);new HexuGroupPurchase(db).fulfillmentAllowed(id);require("SHIPPED".equals(str(o,"status")),"订单尚未发货");require(db.jdbc().queryForObject("select count(*) from hx_refund where order_id=? and status not in ('REJECTED','SUCCESS','CLOSED')",Integer.class,id)==0,"售后处理中不可结算");
        if("WHOLESALE".equals(str(o,"order_type"))){long dest=number(o,"destination_shop_id");for(Map<String,Object> l:db.jdbc().queryForList("select * from hx_order_line where order_id=?",id)){Map<String,Object> source=db.one("select * from hx_sku where shop_id=? and id=?",number(o,"shop_id"),str(l,"sku_id"));require(db.optional("select id from hx_sku where shop_id=? and id=?",dest,str(l,"sku_id"))!=null,"目标商城需先上架对应SKU后入库");db.inventory(dest,str(l,"sku_id"),(int)(number(l,"qty")-number(l,"refunded_qty")),0,"PURCHASE_RECEIVE",id);}}
        new HexuInvitationRewards(db).completed(o);
        new HexuLinkCampaign(db).completed(o);
        for(Map<String,Object> e:db.jdbc().queryForList("select e.*,a.member_id from hx_earning e join hx_agent a on a.id=e.agent_id where e.order_id=? and e.status='PENDING' order by a.member_id,e.id",id)){db.ledger(number(e,"shop_id"),number(e,"member_id"),number(e,"amount")-number(e,"reversed"),str(e,"earning_type"),id);}
        db.jdbc().update("update hx_earning set status='AVAILABLE' where order_id=? and status='PENDING'",id);long earned=(number(o,"total")-number(o,"freight")-number(o,"refunded"))/100;Map<String,Object> rewardSnapshot=db.parse(db.one("select snapshot_json from hx_order_line where order_id=? order by id limit 1",id).get("snapshot_json"));earned=Math.multiplyExact(earned,rewardSnapshot.containsKey("pointsRate")?number(rewardSnapshot,"pointsRate"):1);if(earned>0)db.awardPoints(0,a.id,earned,"ORDER_REWARD",id,rewardSnapshot.containsKey("pointsExpiryDays")?number(rewardSnapshot,"pointsExpiryDays"):365);long shopEarned=Math.multiplyExact((number(o,"total")-number(o,"freight")-number(o,"refunded"))/100,number(rewardSnapshot,"shopPointsRate"));if(shopEarned>0)db.awardPoints(number(o,"shop_id"),a.id,shopEarned,"ORDER_REWARD",id,number(rewardSnapshot,"shopPointsExpiryDays"));db.jdbc().update("update hx_order set status='COMPLETED',completed_at=CURRENT_TIMESTAMP,points_earned=?,shop_points_earned=? where id=?",earned,shopEarned,id);db.audit(a,number(o,"shop_id"),"ORDER_RECEIVE",id,map("status","SHIPPED"),map("status","COMPLETED","pointsEarned",earned,"shopPointsEarned",shopEarned));db.message(number(o,"shop_id"),a.id,"订单完成，收益已结算",id);return detail(a,id,false);
    }
}
