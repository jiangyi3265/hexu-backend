package com.ruoyi.hexu;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.sql.Timestamp;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
/** Team moves preserve agent IDs; all historical orders keep their original shop and snapshots. */
@Service
public class HexuAgencyService {
 private final HexuStore db;
 public HexuAgencyService(HexuStore db){this.db=db;}
 private List<Long> tree(long root,long shop){List<Long> ids=new ArrayList<>();Deque<Long> pending=new ArrayDeque<>();pending.add(root);while(!pending.isEmpty()){long id=pending.remove();require(!ids.contains(id)&&ids.size()<10000,"代理关系循环或团队过大");ids.add(id);for(Map<String,Object>a:db.jdbc().queryForList("select id from hx_agent where parent_id=? and shop_id=? and status='ACTIVE'",id,shop))pending.add(number(a,"id"));}return ids;}
 @Transactional public Map<String,Object> propose(Actor a,long source,Map<String,Object>b){
  db.manage(a,source,"OWNER");Map<String,Object> root=db.one("select * from hx_agent where id=? and shop_id=? and status='ACTIVE'",number(b,"rootAgentId"),source);Map<String,Object> dest=db.shop(number(b,"targetShopId"));
  require(number(dest,"id")!=source&&number(dest,"owner_id")==number(root,"member_id")&&number(root,"rank_no")>=2,"新商城必须由符合职级的团队负责人拥有");require(number(db.shop(source),"owner_id")!=number(root,"member_id"),"原商城拥有者迁出须先完成停业或转让");
  Timestamp effective=Timestamp.valueOf(str(b,"effectiveAt"));require(effective.getTime()>=System.currentTimeMillis()-60000,"迁移生效时间不能追溯");List<Long> agents=tree(number(root,"id"),source);List<Long> members=new ArrayList<>();
  for(long id:agents)for(Map<String,Object> binding:db.jdbc().queryForList("select member_id from hx_binding where shop_id=? and agent_id=?",source,id))members.add(number(binding,"member_id"));
  String ref=id("TM");Map<String,Object> body=map("rootAgentId",root.get("id"),"targetShopId",dest.get("id"),"agentIds",agents,"memberIds",members,"effectiveAt",effective.toString(),"agentCount",agents.size(),"customerCount",members.size());
  db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values(?,?,?,'team_migration','PENDING',?)",ref,source,root.get("member_id"),db.json(body));db.audit(a,source,"TEAM_MIGRATION_PROPOSE",ref,null,body);return db.one("select * from hx_document where id=?",ref);
 }
 @Transactional public Map<String,Object> review(Actor a,String ref,Map<String,Object>b){db.platform(a);Map<String,Object>d=db.one("select * from hx_document where id=? and kind='team_migration' for update",ref);require("PENDING".equals(str(d,"status")),"迁移名单已审核");boolean approve=bool(b,"approve");require(approve||!str(b,"reason").isEmpty(),"驳回必须填写原因");db.jdbc().update("update hx_document set status=?,reviewer=?,review_note=? where id=?",approve?"SCHEDULED":"REJECTED",a.id,str(b,"reason"),ref);db.audit(a,number(d,"shop_id"),"TEAM_MIGRATION_REVIEW",ref,d,b);if(approve)apply(ref);return db.one("select * from hx_document where id=?",ref);}
 @SuppressWarnings("unchecked") private void apply(String ref){
  Map<String,Object>d=db.one("select * from hx_document where id=? for update",ref);if(!"SCHEDULED".equals(str(d,"status")))return;Map<String,Object>b=db.parse(d.get("body_json"));if(Timestamp.valueOf(str(b,"effectiveAt")).getTime()>System.currentTimeMillis())return;
  long source=number(d,"shop_id"),target=number(b,"targetShopId"),rootId=number(b,"rootAgentId");for(long shop:new long[]{Math.min(source,target),Math.max(source,target)})db.one("select id from hx_shop where id=? for update",shop);Map<String,Object>dest=db.shop(target),root=db.one("select * from hx_agent where id=? and shop_id=? for update",rootId,source);require(number(dest,"owner_id")==number(root,"member_id"),"目标商城拥有者已变化，请重新审核");
  List<Long> agents=new ArrayList<>();for(Object id:(List<Object>)b.get("agentIds"))agents.add(Long.valueOf(id.toString()));require(agents.equals(tree(rootId,source)),"团队结构已变化，须重新审核迁移名单");
  for(long id:agents){Map<String,Object>agent=db.one("select * from hx_agent where id=? for update",id);require(db.agent(number(agent,"member_id"),target)==null,"目标商城已有代理身份，请先合并审核");}
  List<Long> members=new ArrayList<>();for(Object id:(List<Object>)b.get("memberIds"))members.add(Long.valueOf(id.toString()));List<Long> actual=new ArrayList<>();for(long id:agents)for(Map<String,Object>x:db.jdbc().queryForList("select member_id from hx_binding where shop_id=? and agent_id=?",source,id))actual.add(number(x,"member_id"));require(new HashSet<>(actual).equals(new HashSet<>(members)),"客户名单已变化，须重新审核");
  for(long member:members){require(db.optional("select * from hx_binding where shop_id=? and member_id=?",target,member)==null,"目标商城已有客户绑定");db.jdbc().update("update hx_binding set shop_id=?,bound_at=CURRENT_TIMESTAMP where shop_id=? and member_id=?",target,source,member);}
  if(root.get("parent_id")!=null){Map<String,Object>parent=db.one("select * from hx_agent where id=?",root.get("parent_id"));require(number(parent,"rank_no")==number(root,"rank_no"),"独立商城只保留已审核的直接同级分红关系");db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values(?,?,?,'peer_authorization','ACTIVE',?)",id("PEER"),target,parent.get("member_id"),db.json(map("childAgentId",rootId,"parentAgentId",parent.get("id"),"migrationId",ref)));}
  for(long id:agents){
   db.jdbc().update("update hx_agent set shop_id=? where id=?",target,id);
   db.jdbc().update("update hx_agent_region set shop_id=? where shop_id=? and agent_id=?",target,source,id);
  }
  db.jdbc().update("update hx_document set status='APPLIED',updated_at=CURRENT_TIMESTAMP where id=?",ref);db.audit(new Actor(number(d,"reviewer"),true,true),source,"TEAM_MIGRATION_APPLIED",ref,null,b);
 }
 @Transactional public void applyDue(){for(Map<String,Object>d:db.jdbc().queryForList("select id from hx_document where kind='team_migration' and status='SCHEDULED' order by created_at limit 100"))apply(str(d,"id"));}
}
