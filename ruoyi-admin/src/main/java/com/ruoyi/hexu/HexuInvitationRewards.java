package com.ruoyi.hexu;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** One-hop invitation rewards, funded only by the original shop owner's remaining order margin. */
public final class HexuInvitationRewards {
 private final HexuStore db;
 public HexuInvitationRewards(HexuStore db){this.db=db;}
 public static void validate(Map<String,Object>b){
  if(!bool(b,"enabled"))return;
  require(!str(b,"name").trim().isEmpty(),"请填写邀请活动名称");
  require(number(b,"firstOrderReward")>=0&&number(b,"thirdOrderReward")>=0&&(number(b,"firstOrderReward")>0||number(b,"thirdOrderReward")>0),"请配置首单或三单奖励上限");
  require(number(b,"rewardCap")>0&&number(b,"minOrderAmount")>=100,"请设置个人累计奖励上限和至少1元的有效订单门槛");
  require(number(b,"startsAt")>=0&&number(b,"expiresAt")>number(b,"startsAt"),"活动起止时间无效");
 }
 private Map<String,Object> current(long shop){
  Map<String,Object>d=db.optional("select * from hx_document where shop_id=? and kind='invitation_campaign' and status='ACTIVE' order by updated_at desc,id desc limit 1",shop);
  if(d==null)return Collections.emptyMap();Map<String,Object>b=db.parse(d.get("body_json"));long now=System.currentTimeMillis();if(!bool(b,"enabled")||number(b,"startsAt")>now||number(b,"expiresAt")<=now)return Collections.emptyMap();b.put("campaignId",d.get("id"));return b;
 }
 public Map<String,Object> capture(long buyer,long shop,long ownerAgent){
  Map<String,Object>r=db.optional("select * from hx_referral where shop_id=? and member_id=?",shop,buyer);if(r==null||number(r,"inviter_agent_id")==ownerAgent||number(r,"inviter_member_id")==buyer)return Collections.emptyMap();
  Map<String,Object>b=current(shop);if(b.isEmpty())return b;Map<String,Object>snapshot=new LinkedHashMap<>();for(String key:Arrays.asList("campaignId","name","firstOrderReward","thirdOrderReward","rewardCap","minOrderAmount","skuIds","startsAt","expiresAt"))if(b.containsKey(key))snapshot.put(key,b.get(key));snapshot.put("inviterAgentId",r.get("inviter_agent_id"));snapshot.put("inviterMemberId",r.get("inviter_member_id"));snapshot.put("ownerAgentId",ownerAgent);return snapshot;
 }
 private Set<String> skus(Map<String,Object>plan){Set<String>ids=new HashSet<>();Object raw=plan.get("skuIds");if(raw instanceof Collection)for(Object id:(Collection<?>)raw)ids.add(String.valueOf(id));else if(raw!=null)for(String id:raw.toString().split(","))if(!id.trim().isEmpty())ids.add(id.trim());return ids;}
 @SuppressWarnings("unchecked") private Map<String,Object> plan(List<Map<String,Object>>lines){if(lines.isEmpty())return Collections.emptyMap();Object raw=db.parse(lines.get(0).get("snapshot_json")).get("invitation");return raw instanceof Map?(Map<String,Object>)raw:Collections.emptyMap();}
 private long qualifying(List<Map<String,Object>>lines,Map<String,Object>plan){Set<String>ids=skus(plan);long amount=0;for(Map<String,Object>l:lines)if(ids.isEmpty()||ids.contains(str(l,"sku_id")))amount=Math.addExact(amount,number(l,"paid")-number(l,"refunded_paid"));return amount;}
 public void completed(Map<String,Object>order){
  String id=str(order,"id");List<Map<String,Object>>lines=db.jdbc().queryForList("select * from hx_order_line where order_id=? order by id",id);Map<String,Object>plan=plan(lines);if(plan.isEmpty()||number(plan,"expiresAt")<System.currentTimeMillis())return;
  long eligible=qualifying(lines,plan),threshold=number(plan,"minOrderAmount"),shop=number(order,"shop_id"),buyer=number(order,"buyer_id"),inviter=number(plan,"inviterAgentId");if(eligible<threshold)return;
  db.one("select id from hx_agent where id=? for update",inviter);
  if(db.optional("select order_id from hx_invitation_order where order_id=?",id)!=null)return;
  db.jdbc().update("insert into hx_invitation_order(order_id,shop_id,campaign_id,buyer_id,inviter_agent_id,qualifying_amount,threshold_amount) values(?,?,?,?,?,?,?)",id,shop,str(plan,"campaignId"),buyer,inviter,eligible,threshold);
  long prior=db.jdbc().queryForObject("select count(*) from hx_invitation_order r join hx_order o on o.id=r.order_id where r.campaign_id=? and r.buyer_id=? and r.order_id<>? and r.qualifying_amount>=r.threshold_amount and o.status='COMPLETED'",Long.class,str(plan,"campaignId"),buyer,id);int stage=prior==0?1:prior==2?3:0;if(stage==0)return;
  if(db.optional("select order_id from hx_invitation_reward where campaign_id=? and buyer_id=? and milestone=?",str(plan,"campaignId"),buyer,stage)!=null)return;
  long requested=number(plan,stage==1?"firstOrderReward":"thirdOrderReward"),used=db.jdbc().queryForObject("select coalesce(sum(amount),0) from hx_invitation_reward where campaign_id=? and inviter_agent_id=?",Long.class,str(plan,"campaignId"),inviter);
  List<Map<String,Object>>margins=db.jdbc().queryForList("select line_id,sum(amount-reversed) as margin from hx_earning where order_id=? and agent_id=? group by line_id order by line_id",id,number(plan,"ownerAgentId"));long margin=0;for(Map<String,Object>l:margins){require(number(l,"margin")>=0,"商城可分配毛利异常");margin=Math.addExact(margin,number(l,"margin"));}
  long amount=Math.min(Math.min(requested,Math.max(0,number(plan,"rewardCap")-used)),margin);
  db.jdbc().update("insert into hx_invitation_reward(campaign_id,buyer_id,milestone,order_id,shop_id,inviter_agent_id,amount,snapshot_json) values(?,?,?,?,?,?,?,?)",str(plan,"campaignId"),buyer,stage,id,shop,inviter,amount,db.json(plan));
  long accumulated=0,previous=0;for(Map<String,Object>l:margins){accumulated+=number(l,"margin");long target=margin==0?0:SettlementCalculator.proportion(amount,accumulated,margin),part=target-previous;previous=target;if(part==0)continue;db.jdbc().update("insert into hx_earning(order_id,line_id,shop_id,agent_id,earning_type,amount) values(?,?,?,?,?,?)",id,l.get("line_id"),shop,inviter,"INVITE_REWARD",part);db.jdbc().update("insert into hx_earning(order_id,line_id,shop_id,agent_id,earning_type,amount) values(?,?,?,?,?,?)",id,l.get("line_id"),shop,number(plan,"ownerAgentId"),"INVITE_COST",-part);}
  db.audit(new Actor(0,true,true),shop,"INVITATION_REWARD",id,null,map("milestone",stage,"requested",requested,"amount",amount,"campaignId",plan.get("campaignId")));if(amount>0)db.message(shop,number(plan,"inviterMemberId"),"邀请订单奖励已结算",id);
 }
 public void reversed(String order){Map<String,Object>record=db.optional("select * from hx_invitation_order where order_id=?",order);if(record==null)return;List<Map<String,Object>>lines=db.jdbc().queryForList("select * from hx_order_line where order_id=? order by id",order);db.jdbc().update("update hx_invitation_order set qualifying_amount=? where order_id=?",qualifying(lines,plan(lines)),order);}
 public Map<String,Object> status(Actor actor,long shop){db.member(actor);db.shop(shop);Map<String,Object>campaign=current(shop),agent=db.agent(actor.id,shop);long invited=0,qualified=0,earned=0;if(agent!=null){long id=number(agent,"id");invited=db.jdbc().queryForObject("select count(*) from hx_referral where shop_id=? and inviter_agent_id=?",Long.class,shop,id);qualified=db.jdbc().queryForObject("select count(distinct buyer_id) from hx_invitation_order where shop_id=? and inviter_agent_id=? and qualifying_amount>=threshold_amount",Long.class,shop,id);earned=db.jdbc().queryForObject("select coalesce(sum(amount-reversed),0) from hx_earning where shop_id=? and agent_id=? and earning_type='INVITE_REWARD'",Long.class,shop,id);}return map("enabled",!campaign.isEmpty(),"campaign",campaign,"canInvite",agent!=null,"invited",invited,"qualified",qualified,"earned",earned);}
}
