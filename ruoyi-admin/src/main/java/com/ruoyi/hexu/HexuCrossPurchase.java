package com.ruoyi.hexu;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
/** Scoped approvals reserve quantities through orders; cancellation releases the reservation. */
public class HexuCrossPurchase {
 private final HexuStore db;
 public HexuCrossPurchase(HexuStore db){this.db=db;}
 @SuppressWarnings("unchecked") public static void validate(HexuStore db,long shop,Map<String,Object>b){
  require(b.get("items") instanceof List,"请指定跨店采购商品及数量上限");List<Map<String,Object>>items=(List<Map<String,Object>>)b.get("items");require(!items.isEmpty()&&items.size()<=100,"授权商品须为1至100项");Set<String>ids=new HashSet<>();for(Map<String,Object>i:items){require(ids.add(str(i,"skuId"))&&number(i,"qty")>=1&&number(i,"qty")<=100000,"授权商品或数量不合法");db.one("select id from hx_sku where shop_id=? and id=? and status='ACTIVE'",shop,str(i,"skuId"));}
 }
 @SuppressWarnings("unchecked") public Map<String,Object> find(Actor a,long shop,Map<String,? extends Number>requested,boolean lock){
  for(Map<String,Object>d:db.jdbc().queryForList("select * from hx_document where shop_id=? and member_id=? and kind='cross_purchase' and status='APPROVED' order by updated_at desc,id"+(lock?" for update":""),shop,a.id)){
   Map<String,Object>b=db.parse(d.get("body_json")),source=db.agent(a.id,number(b,"sourceShopId"));if(source==null||number(b,"expiresAt")<=System.currentTimeMillis()||!(b.get("items") instanceof List))continue;
   Map<String,Long>limits=new HashMap<>();for(Map<String,Object>i:(List<Map<String,Object>>)b.get("items"))limits.put(str(i,"skuId"),number(i,"qty"));
   long orders=db.jdbc().queryForObject("select count(*) from hx_order where cross_authorization_id=? and status<>'CANCELLED'",Long.class,d.get("id"));if(bool(b,"singleUse")&&orders>0)continue;
   boolean fits=true;for(Map.Entry<String,? extends Number>e:requested.entrySet()){long used=db.jdbc().queryForObject("select coalesce(sum(l.qty),0) from hx_order_line l join hx_order o on o.id=l.order_id where o.cross_authorization_id=? and o.status<>'CANCELLED' and l.sku_id=?",Long.class,d.get("id"),e.getKey());if(!limits.containsKey(e.getKey())||used+e.getValue().longValue()>limits.get(e.getKey()))fits=false;}
   if(fits){d.put("authorizedRank",Math.min(number(source,"rank_no"),number(b,"authorizedRank")));return d;}
  }return null;
 }
 public int rank(Actor a,long shop,String sku){Map<String,Object>d=find(a,shop,sku==null?Collections.emptyMap():Collections.singletonMap(sku,1),false);return d==null?0:(int)number(d,"authorizedRank");}
 @SuppressWarnings("unchecked") public long remaining(Map<String,Object> authorization,String sku){
  Map<String,Object> body=db.parse(authorization.get("body_json"));
  long limit=0;for(Map<String,Object> item:(List<Map<String,Object>>)body.get("items"))if(sku.equals(str(item,"skuId"))){limit=number(item,"qty");break;}
  long used=db.jdbc().queryForObject("select coalesce(sum(l.qty),0) from hx_order_line l join hx_order o on o.id=l.order_id where o.cross_authorization_id=? and o.status<>'CANCELLED' and l.sku_id=?",Long.class,authorization.get("id"),sku);
  return Math.max(0,limit-used);
 }
}
