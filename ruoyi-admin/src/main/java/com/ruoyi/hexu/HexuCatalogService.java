package com.ruoyi.hexu;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Service
public class HexuCatalogService {
    private final HexuStore db;
    public HexuCatalogService(HexuStore db){this.db=db;}
    @Transactional public Map<String,Object> warning(Actor a,long shop,Map<String,Object>b){db.manage(a,shop,"WAREHOUSE");long qty=number(b,"warningQty");require(qty>=0&&qty<=Integer.MAX_VALUE,"预警阈值无效");Map<String,Object>old=db.one("select * from hx_sku where shop_id=? and id=?",shop,str(b,"skuId"));db.jdbc().update("update hx_sku set warning_qty=? where shop_id=? and id=?",qty,shop,str(b,"skuId"));db.audit(a,shop,"STOCK_WARNING",str(b,"skuId"),map("warningQty",old.get("warning_qty")),b);return map("updated",true);}
    public int rank(Actor a,long shop){Map<String,Object>ag=db.agent(a.id,shop);return ag!=null?(int)number(ag,"rank_no"):new HexuCrossPurchase(db).rank(a,shop,null);}

    public Map<String,Object> access(Actor a,long target){
        db.member(a);
        db.shop(target);
        Map<String,Object> binding=db.optional("select b.*,s.name from hx_binding b join hx_shop s on s.id=b.shop_id where b.member_id=? and s.status='ACTIVE' order by b.bound_at,b.shop_id limit 1",a.id);
        boolean current=db.optional("select * from hx_binding where member_id=? and shop_id=?",a.id,target)!=null;
        String policy=str(db.config(target,"cross_shop_policy"),"mode");
        boolean locked=rank(a,target)==0&&binding!=null&&!current&&number(binding,"shop_id")!=target&&!"ALLOW_RETAIL".equals(policy);
        Map<String,Object> crossOrder=locked?db.optional("select id,status,expires_at from hx_order where shop_id=? and buyer_id=? and cross_authorization_id is not null order by created_at desc,id desc limit 1",target,a.id):null;
        Object pendingOrderId="";
        if(crossOrder!=null&&"UNPAID".equals(str(crossOrder,"status"))){
            Object expires=crossOrder.get("expires_at");
            if(expires instanceof java.util.Date&&((java.util.Date)expires).getTime()>System.currentTimeMillis())pendingOrderId=crossOrder.get("id");
        }
        return map("allowed",!locked,"crossOrderHistory",crossOrder!=null,"pendingOrderId",pendingOrderId,
            "policy",policy.isEmpty()?"RETURN_HOME":policy,"homeShopId",binding==null?target:binding.get("shop_id"),"homeShopName",binding==null?"":binding.get("name"));
    }
    @Transactional public Map<String,Object> status(Actor a,long shop,Map<String,Object>b){db.manage(a,shop,"CATALOG");Map<String,Object>sku=db.one("select * from hx_sku where shop_id=? and id=? for update",shop,str(b,"id"));String status=str(b,"status");require(Arrays.asList("ACTIVE","INACTIVE","FROZEN","REJECTED").contains(status),"商品状态无效");if(Arrays.asList("PENDING","FROZEN","REJECTED").contains(str(sku,"status"))||Arrays.asList("FROZEN","REJECTED").contains(status))db.platform(a);db.jdbc().update("update hx_sku set status=? where shop_id=? and id=?",status,shop,str(b,"id"));db.audit(a,shop,"SKU_STATUS",str(b,"id"),sku,b);return db.one("select * from hx_sku where shop_id=? and id=?",shop,str(b,"id"));}
    public List<Map<String,Object>> shops(){return db.jdbc().queryForList("select id,name,county,kind,status from hx_shop where status='ACTIVE' and kind<>'WHOLESALE' order by id");}
    public List<Map<String,Object>> guestProducts(long shop){
        Map<String,Object> store=db.shop(shop);
        require("ACTIVE".equals(str(store,"status"))&&!"WHOLESALE".equals(str(store,"kind")),"商城暂不开放游客浏览");
        List<Map<String,Object>> products=new ArrayList<>();
        for(Map<String,Object> row:db.jdbc().queryForList("select s.id,s.name,s.spec,s.asset,s.retail as price,s.min_qty,d.category,d.brand,d.description,d.gallery_json from hx_sku s left join hx_sku_detail d on d.shop_id=s.shop_id and d.sku_id=s.id where s.shop_id=? and s.status='ACTIVE' order by s.id",shop)){
            Object gallery=row.remove("gallery_json");
            row.put("gallery",gallery==null?Collections.emptyList():db.parse(gallery).getOrDefault("items",Collections.emptyList()));
            row.put("shopId",shop);
            products.add(row);
        }
        return products;
    }
    public List<Map<String,Object>> products(Actor a,long shop,boolean management){return products(a,shop,management,null);}
    public List<Map<String,Object>> products(Actor a,long shop,boolean management,Long destinationShopId){
        Map<String,Object> s=db.one("select * from hx_shop where id=?",shop);
        if(management)db.manage(a,shop,"CATALOG");
        else if(!"ACTIVE".equals(str(s,"status")))return Collections.emptyList();
        Map<String,Object> ag=db.agent(a.id,shop);
        int rank=rank(a,shop);
        if(destinationShopId!=null)require(!management&&"WHOLESALE".equals(str(s,"kind")),"目标商城仅适用于批发采购");
        if(!management&&"WHOLESALE".equals(str(s,"kind"))){
            require(!a.staff,"批发采购请使用代理账户");
            if(destinationShopId!=null){
                Map<String,Object> destination=db.shop(destinationShopId);
                require(number(destination,"owner_id")==a.id,"只能为本人经营的商城采购");
                ag=db.agent(a.id,destinationShopId);
                require(ag!=null&&number(ag,"rank_no")>=2,"不具备批发采购资格");
            }else ag=db.optional("select a.* from hx_agent a join hx_shop s on s.id=a.shop_id and s.owner_id=a.member_id where a.member_id=? and a.status='ACTIVE' and s.status='ACTIVE' order by a.rank_no desc limit 1",a.id);
            require(ag!=null,"仅商城拥有者可批发采购");
            rank=(int)number(ag,"rank_no");
        }
        List<Map<String,Object>> result=db.jdbc().queryForList("select * from hx_sku where shop_id=?"+(management?"":" and status='ACTIVE'")+" order by id",shop);
        for(Map<String,Object>p:result){p.putAll(new HexuProductData(db).detail(shop,str(p,"id")));if(management)p.put("standard",db.optional("select id from hx_sku where shop_id=900 and id=?",str(p,"id"))!=null); }
        for(Map<String,Object>p:result)p.put("in_transit",db.jdbc().queryForObject("select coalesce(sum(l.qty-l.refunded_qty),0) from hx_order_line l join hx_order o on o.id=l.order_id where o.destination_shop_id=? and o.order_type='WHOLESALE' and o.status='SHIPPED' and l.sku_id=?",Long.class,shop,p.get("id")));
        HexuCrossPurchase crossPurchase=ag==null&&!"WHOLESALE".equals(str(s,"kind"))?new HexuCrossPurchase(db):null;
        if(!management)for(Map<String,Object> p:result){
            p.put("sales",db.jdbc().queryForObject("select coalesce(sum(l.qty-l.refunded_qty),0) from hx_order_line l join hx_order o on o.id=l.order_id where o.shop_id=? and l.sku_id=? and o.status in ('PAID','SHIPPED','COMPLETED') and o.created_at>=?",Long.class,shop,str(p,"id"),java.sql.Timestamp.valueOf(java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay())));
            Map<String,Object> authorization=crossPurchase==null?null:crossPurchase.find(a,shop,Collections.singletonMap(str(p,"id"),1),false);
            int visibleRank=authorization!=null?(int)number(authorization,"authorizedRank"):crossPurchase==null?rank:0;
            if(authorization!=null)p.put("crossPurchaseRemaining",crossPurchase.remaining(authorization,str(p,"id")));
            long price=number(p,visibleRank==1?"cloud_price":visibleRank==2?"center_price":visibleRank==3?"owner_price":"retail");
            p.put("retailPrice",number(p,"retail"));p.put("price",price);p.put("stock",p.get("available"));
            p.remove("retail");p.remove("cloud_price");p.remove("center_price");p.remove("owner_price");p.remove("retail_min");p.remove("retail_max");
        }return result;
    }
    @Transactional public Map<String,Object> bind(Actor a,long shop,String invite){db.member(a);Map<String,Object>s=db.shop(shop);db.one("select id from hx_member where id=? for update",a.id);Map<String,Object> existing=db.optional("select * from hx_binding where member_id=? and shop_id=?",a.id,shop);if(existing!=null)return existing;
        Map<String,Object> agent;
        if(invite!=null&&!invite.isEmpty()){Map<String,Object> link=db.one("select * from hx_invite where id=? and shop_id=? and status='ACTIVE' and expires_at>CURRENT_TIMESTAMP",invite,shop);agent=db.one("select * from hx_agent where id=? and shop_id=? and status='ACTIVE'",number(link,"agent_id"),shop);}else agent=db.agent(number(s,"owner_id"),shop);
        require(agent!=null,"商城拥有者尚未配置代理身份");db.jdbc().update("insert into hx_binding(member_id,shop_id,agent_id) values(?,?,?)",a.id,shop,number(agent,"id"));if(invite!=null&&!invite.isEmpty()&&number(agent,"member_id")!=a.id)db.jdbc().update("insert into hx_referral(shop_id,member_id,inviter_agent_id,inviter_member_id,invite_id) values(?,?,?,?,?)",shop,a.id,agent.get("id"),agent.get("member_id"),invite);db.audit(a,shop,"BIND",String.valueOf(a.id),null,agent.get("id"));return db.one("select * from hx_binding where member_id=? and shop_id=?",a.id,shop);
    }
    @Transactional public Map<String,Object> saveSku(Actor a,long shop,Map<String,Object> p){
        db.manage(a,shop,"CATALOG");
        String sku=str(p,"id");
        require(sku.matches("[A-Za-z0-9_-]{1,64}"),"商品编号格式错误");
        String name=str(p,"name"),spec=str(p,"spec");
        boolean validText=p.get("name") instanceof String
            &&(p.get("spec")==null||p.get("spec") instanceof String)
            &&!HexuText.trim(name).isEmpty()&&name.length()<=160&&spec.length()<=160
            &&name.codePoints().noneMatch(Character::isISOControl)
            &&spec.codePoints().noneMatch(Character::isISOControl);
        require(validText&&str(p,"asset").length()<=100,"商品名称、规格或封面格式无效");
        long retail=number(p,"retail"),cloud=number(p,"cloud_price"),center=number(p,"center_price"),owner=number(p,"owner_price");
        require(retail>=cloud&&cloud>=center&&center>=owner&&owner>0&&retail<=100000000,"价格须为正整数分且零售≥云≥中心≥总代");
        for(String key:Arrays.asList("box_size","min_boxes","min_qty"))require(number(p,key)>0&&number(p,key)<=100000,"箱规和起订量须为1至100000");
        Map<String,Object> old=db.optional("select * from hx_sku where shop_id=? and id=? for update",shop,sku);
        if(old==null)require(str(p,"asset").startsWith("FILE")&&p.get("gallery") instanceof List&&((List<?>)p.get("gallery")).contains(str(p,"asset")),"请先上传商品主图");
        if(old!=null&&"FROZEN".equals(str(old,"status")))db.platform(a);
        Map<String,Object>standard=db.optional("select * from hx_sku where shop_id=900 and id=?",sku);HexuProductData data=new HexuProductData(db);if(!a.platform&&standard!=null){require(cloud==number(standard,"cloud_price")&&center==number(standard,"center_price")&&owner==number(standard,"owner_price"),"总部标准商品职级价仅能由平台调整");data.permittedRetail(shop,sku,retail);}
        if(shop!=900){Map<String,Object>own=db.agent(number(db.one("select owner_id from hx_shop where id=?",shop),"owner_id"),shop);if(own!=null){Map<String,Object>rule=db.one("select * from hx_rule where effective_at<=CURRENT_TIMESTAMP order by effective_at desc,id desc limit 1");for(Map<String,Object>ag:db.jdbc().queryForList("select id from hx_agent where shop_id=? and status='ACTIVE'",shop))SettlementCalculator.calculate(retail,1,new long[]{retail,cloud,center,owner},new int[]{0,(int)number(rule,"cloud_bps"),(int)number(rule,"center_bps"),(int)number(rule,"owner_bps")},db.chain(number(ag,"id"),shop),new SettlementCalculator.Node(number(own,"id"),number(own,"member_id"),(int)number(own,"rank_no")),true);}}
        if(old==null)db.jdbc().update("insert into hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,box_size,min_boxes,min_qty,status) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",sku,shop,str(p,"name"),str(p,"spec"),str(p,"asset"),retail,cloud,center,owner,number(p,"box_size"),number(p,"min_boxes"),number(p,"min_qty"),a.platform?"ACTIVE":"PENDING");
        else db.jdbc().update("update hx_sku set name=?,spec=?,asset=?,retail=?,cloud_price=?,center_price=?,owner_price=?,box_size=?,min_boxes=?,min_qty=? where shop_id=? and id=?",str(p,"name"),str(p,"spec"),str(p,"asset"),retail,cloud,center,owner,number(p,"box_size"),number(p,"min_boxes"),number(p,"min_qty"),shop,sku);
        if(p.containsKey("point_price")){require(number(p,"point_price")>=0,"兑换积分不可为负数");db.jdbc().update("update hx_sku set point_price=? where shop_id=? and id=?",number(p,"point_price"),shop,sku);}data.save(a,shop,sku,p);
        if(p.containsKey("available")){long target=number(p,"available"),previous=old==null?0:number(old,"available");require(target>=0&&target<=Integer.MAX_VALUE,"可售库存无效");if(target!=previous){db.manage(a,shop,"WAREHOUSE");require(old==null||p.containsKey("expectedAvailable")&&number(p,"expectedAvailable")==previous,"库存已变化，请刷新后重新修改");require(str(p,"stockReason").trim().length()>=2,"修改库存必须填写调整原因");db.inventory(shop,sku,Math.toIntExact(target-previous),0,"ADJUST",id("STOCK"));}}
        if(p.containsKey("publish")&&(a.platform||old!=null&&Arrays.asList("ACTIVE","INACTIVE").contains(str(old,"status"))&&standard!=null))db.jdbc().update("update hx_sku set status=? where shop_id=? and id=?",bool(p,"publish")?"ACTIVE":"INACTIVE",shop,sku);
        if(!a.platform&&standard==null)db.jdbc().update("update hx_sku set status='PENDING' where shop_id=? and id=?",shop,sku);
        db.audit(a,shop,"SKU_SAVE",sku,old,p);Map<String,Object>result=db.one("select * from hx_sku where shop_id=? and id=?",shop,sku);result.putAll(data.detail(shop,sku));return result;
    }
    @Transactional public Map<String,Object> boxRules(Actor a,long shop,Map<String,Object>b){db.manage(a,shop,"CATALOG");Map<String,Object>s=db.one("select * from hx_shop where id=? for update",shop);if("WHOLESALE".equals(str(s,"kind")))db.platform(a);Map<String,Object>p=db.one("select * from hx_sku where shop_id=? and id=? for update",shop,str(b,"skuId"));for(String k:Arrays.asList("box_size","min_boxes","min_qty","mix_units"))require(number(b,k)>0&&number(b,k)<=100000,"箱规、起订量和箱容积分须为正整数");require(str(b,"mix_group").matches("[A-Za-z0-9_-]{0,64}"),"混批组编号格式错误");Map<String,Object>settings=db.parse(s.get("settings"));if(b.containsKey("mixedEnabled")){if(bool(b,"mixedEnabled"))require(number(b,"mixCapacity")>0&&number(b,"mixCapacity")<=1000000&&number(b,"minMixBoxes")>0&&number(b,"minMixBoxes")<=100000,"混批箱容与最低箱数无效");settings.put("mixedEnabled",bool(b,"mixedEnabled"));settings.put("mixCapacity",number(b,"mixCapacity"));settings.put("minMixBoxes",number(b,"minMixBoxes"));db.jdbc().update("update hx_shop set settings=? where id=?",db.json(settings),shop);}
        db.jdbc().update("update hx_sku set box_size=?,min_boxes=?,min_qty=?,mix_group=?,mix_units=? where shop_id=? and id=?",number(b,"box_size"),number(b,"min_boxes"),number(b,"min_qty"),str(b,"mix_group"),number(b,"mix_units"),shop,str(b,"skuId"));new HexuProductData(db).save(a,shop,str(b,"skuId"),b);db.audit(a,shop,"SKU_BOX_RULES",str(b,"skuId"),map("sku",p,"settings",s.get("settings")),b);return map("updated",true);
    }
    @Transactional public Map<String,Object> stockAdjust(Actor a,long shop,Map<String,Object> p){db.manage(a,shop,"WAREHOUSE");String reason=str(p,"reason");require(reason.trim().length()>=2,"库存调整必须填写原因");int qty=Math.toIntExact(number(p,"quantity"));require(qty!=0,"调整数量不能为零");db.inventory(shop,str(p,"skuId"),qty,0,"ADJUST",str(p,"reference"));db.audit(a,shop,"INVENTORY_ADJUST",str(p,"skuId"),null,p);return map("updated",true);}
    public Map<String,Object> invitationContext(Actor a,long shop,String invite){
        db.member(a);Map<String,Object> store=db.shop(shop);
        Map<String,Object> binding=db.optional("select b.agent_id,a.member_id,a.rank_no,m.name,b.bound_at from hx_binding b join hx_agent a on a.id=b.agent_id join hx_member m on m.id=a.member_id where b.member_id=? and b.shop_id=?",a.id,shop);
        Map<String,Object> current=binding==null?null:map("agentId",binding.get("agent_id"),"memberId",binding.get("member_id"),"name",binding.get("name"),"rank",binding.get("rank_no"),"boundAt",binding.get("bound_at"));
        Map<String,Object> link=db.optional("select * from hx_invite where id=? and shop_id=?",invite,shop);
        Map<String,Object> inviter=null;String reason="邀请无效，请重新获取邀请链接";Object expiresAt=null;
        if(link!=null){
            expiresAt=((java.util.Date)link.get("expires_at")).getTime();
            if(!"ACTIVE".equals(str(link,"status")))reason="邀请已停用，请重新获取邀请链接";
            else if(db.optional("select id from hx_invite where id=? and expires_at>CURRENT_TIMESTAMP",invite)==null)reason="邀请已过期，请重新获取邀请链接";
            else{
                Map<String,Object> agent=db.optional("select a.id,a.member_id,a.rank_no,m.name from hx_agent a join hx_member m on m.id=a.member_id where a.id=? and a.shop_id=? and a.status='ACTIVE' and m.status='ACTIVE'",link.get("agent_id"),shop);
                if(agent==null)reason="邀请人代理身份已失效";
                else{inviter=map("id",agent.get("id"),"memberId",agent.get("member_id"),"name",agent.get("name"),"rank",agent.get("rank_no"));reason="";}
            }
        }
        return map("valid",inviter!=null,"reason",reason,"shop",map("id",shop,"name",store.get("name")),"inviter",inviter,"expiresAt",expiresAt,"bound",binding!=null,"currentBinding",current);
    }
    @Transactional public Map<String,Object> invite(Actor a,long shop){db.member(a);db.shop(shop);Map<String,Object> ag=db.agent(a.id,shop);require(ag!=null,"审核通过的代理才能生成邀请");String id=id("IV");java.sql.Timestamp expires=java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(30L*86400));db.jdbc().update("insert into hx_invite(id,shop_id,agent_id,channel,expires_at) values(?,?,?,?,?)",id,shop,ag.get("id"),"SHARE",expires);return map("invite",id,"shopId",shop,"expiresAt",expires);}
}
