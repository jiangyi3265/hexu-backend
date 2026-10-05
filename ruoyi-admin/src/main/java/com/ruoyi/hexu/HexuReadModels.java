package com.ruoyi.hexu;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Page read models use historical transaction fields and redact other accounts' private details. */
public final class HexuReadModels {
 private final HexuStore db;
 public HexuReadModels(HexuStore db){this.db=db;}
 /** Keep cash refunds in cents and expose point returns separately for redemption orders. */
 public List<Map<String,Object>> refunds(Actor actor,long shop,boolean management){
  if(management)db.manage(actor,shop,"SUPPORT","WAREHOUSE");else db.member(actor);
  String sql="select r.*,coalesce(l.sku_id,'') sku_id,o.order_type,o.points_scope,o.points_used,l.qty order_line_qty from hx_refund r left join hx_order o on o.id=r.order_id and o.shop_id=r.shop_id left join hx_order_line l on l.id=r.line_id and l.order_id=r.order_id where r.shop_id=?";
  List<Map<String,Object>> rows=management?db.jdbc().queryForList(sql+" order by r.created_at desc limit 500",shop):db.jdbc().queryForList(sql+" and r.member_id=? order by r.created_at desc",shop,actor.id);
  for(Map<String,Object> row:rows){
   if("POINTS".equals(str(row,"order_type"))&&number(row,"order_line_qty")>0)row.put("points_return",Math.multiplyExact(number(row,"points_used"),number(row,"qty"))/number(row,"order_line_qty"));
   else {row.remove("order_type");row.remove("points_scope");}
   row.remove("points_used");row.remove("order_line_qty");
  }
  return rows;
 }
 private String phone(Object raw){String value=String.valueOf(raw==null?"":raw);return value.matches("[0-9]{11}")?value.substring(0,3)+"****"+value.substring(7):"";}
 public List<Map<String,Object>> agents(Actor a,long shop){db.manage(a,shop,"OPERATOR","OWNER","FINANCE");List<Map<String,Object>>rows=db.jdbc().queryForList("select a.*,m.name,m.phone,(select count(*) from hx_binding b where b.shop_id=a.shop_id and b.agent_id=a.id) customer_count,(select count(*) from hx_agent c where c.shop_id=a.shop_id and c.parent_id=a.id and c.status='ACTIVE') child_count from hx_agent a join hx_member m on m.id=a.member_id where a.shop_id=? order by a.id limit 500",shop);for(Map<String,Object>r:rows)r.put("phone",phone(r.get("phone")));return rows;}
 public List<Map<String,Object>> customers(Actor a,long shop){db.manage(a,shop,"OPERATOR","OWNER");List<Map<String,Object>>rows=db.jdbc().queryForList("select b.*,m.name,m.phone,(select count(*) from hx_order o where o.buyer_id=b.member_id and o.shop_id=b.shop_id) order_count,(select inviter_agent_id from hx_referral r where r.shop_id=b.shop_id and r.member_id=b.member_id) inviter_agent_id from hx_binding b join hx_member m on m.id=b.member_id where b.shop_id=? order by b.bound_at desc,b.member_id limit 500",shop);for(Map<String,Object>r:rows)r.put("phone",phone(r.get("phone")));return rows;}
 public Map<String,Object> agentSummary(Actor actor,long shop,long id){db.manage(actor,shop,"OPERATOR","OWNER");Map<String,Object>a=db.one("select a.*,m.name from hx_agent a join hx_member m on m.id=a.member_id where a.id=? and a.shop_id=?",id,shop);List<Map<String,Object>>chain=new ArrayList<>();if("ACTIVE".equals(str(a,"status")))for(SettlementCalculator.Node n:db.chain(id,shop))chain.add(map("id",n.agentId,"rank",n.rank,"name",db.one("select name from hx_member where id=?",n.memberId).get("name")));a.put("chain",chain);a.put("assessment","ACTIVE".equals(str(a,"status"))?new HexuAssessment(db).evaluate(number(a,"member_id"),shop):Collections.emptyMap());a.put("customers",db.jdbc().queryForObject("select count(*) from hx_binding where shop_id=? and agent_id=?",Long.class,shop,id));return a;}
 public Map<String,Object> earning(Actor actor,long id){db.member(actor);Map<String,Object>e=db.one("select e.*,a.member_id from hx_earning e join hx_agent a on a.id=e.agent_id where e.id=?",id);require(number(e,"member_id")==actor.id,"无权查看其他代理收益");Map<String,Object>l=db.one("select id,sku_id,name,qty,unit_price,paid,refunded_qty,refunded_paid,snapshot_json from hx_order_line where id=?",e.get("line_id")),snapshot=db.parse(l.get("snapshot_json"));Long cost=null;Object chain=snapshot.get("chain"),prices=snapshot.get("prices");if(chain instanceof List&&prices instanceof List)for(Object item:(List<?>)chain){Map<String,Object>n=(Map<String,Object>)item;if(number(n,"agentId")==number(e,"agent_id")){int rank=(int)number(n,"rank");if(rank>=1&&rank<((List<?>)prices).size())cost=Long.valueOf(String.valueOf(((List<?>)prices).get(rank)));}}l.remove("snapshot_json");e.put("line",l);e.put("settlementPrice",cost);e.put("ruleId",snapshot.get("ruleId"));e.put("remaining",number(e,"amount")-number(e,"reversed"));return e;}
 private String withdrawalAccountLabel(Map<String,Object> body,String channel){
  if("BALANCE".equals(channel))return str(body,"accountName").trim().isEmpty()?"系统余额账户":"系统余额 · "+str(body,"accountName").trim();
  if("WECHAT".equals(channel))return str(body,"accountName").trim().isEmpty()?"微信零钱账户":"微信零钱 · "+str(body,"accountName").trim();
  String masked=str(body,"masked").trim();
  if(!masked.isEmpty())return masked;
  String bank=str(body,"bank").trim(),bankName=str(body,"bankName").trim();
  if(!bank.isEmpty())return (bankName+" ****"+bank.substring(Math.max(0,bank.length()-4))).trim();
  return bankName.isEmpty()?"银行卡账户待核对":bankName;
 }
 public Map<String,Object> withdrawal(Actor actor,String id,boolean management){Map<String,Object>w=db.one("select * from hx_withdrawal where id=?",id);if(management)db.manage(actor,number(w,"shop_id"),"FINANCE","OWNER");else{db.member(actor);require(number(w,"member_id")==actor.id,"无权查看他人提现");}Map<String,Object>d=db.optional("select body_json from hx_document where id=?",w.get("account_ref"));Map<String,Object>b=d==null?Collections.emptyMap():db.parse(d.get("body_json"));SensitiveFields.redact(b);w.put("accountLabel",withdrawalAccountLabel(b,str(w,"channel")));w.put("memberName",db.one("select name from hx_member where id=?",w.get("member_id")).get("name"));Map<String,Object>balance=db.optional("select available,frozen from hx_wallet where shop_id=? and member_id=?",w.get("shop_id"),w.get("member_id"));w.put("wallet",balance==null?map("available",0,"frozen",0):balance);return w;}
 public Map<String,Object> earningSummary(Actor a,long shop){db.manage(a,shop,"FINANCE");return db.one("select coalesce(sum(case when status='PENDING' then amount-reversed else 0 end),0) pending,coalesce(sum(case when status='AVAILABLE' then amount-reversed else 0 end),0) settled,coalesce(sum(frozen_amount),0) frozen from hx_earning where shop_id=?",shop);}
 public Map<String,Object> reversal(Actor a,long shop,String order){db.manage(a,shop,"FINANCE");Map<String,Object>o=db.one("select id,shop_id,total,refunded,status from hx_order where id=? and shop_id=?",order,shop);o.put("earnings",db.jdbc().queryForList("select e.id,e.agent_id,e.line_id,e.earning_type,e.amount,e.reversed,e.amount-e.reversed remaining from hx_earning e where e.order_id=? order by e.id",order));o.put("refunds",db.jdbc().queryForList("select id,line_id,qty,amount,status from hx_refund where order_id=? order by created_at",order));o.put("lines",db.jdbc().queryForList("select sku_id,name,qty,unit_price,paid,refunded_qty from hx_order_line where order_id=?",order));return o;}
}
