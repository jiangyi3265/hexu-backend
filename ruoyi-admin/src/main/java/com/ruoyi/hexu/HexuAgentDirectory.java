package com.ruoyi.hexu;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

/** Read-only relationship browsing plus separately managed operating regions. */
@Service
public class HexuAgentDirectory {
    private final HexuStore db;

    public HexuAgentDirectory(HexuStore db){this.db=db;}

    private void canRead(Actor actor,long shop){db.manage(actor,shop,"OPERATOR","FINANCE");}

    private Map<String,Object> agent(long shop,long id){
        require(id>0,"请选择代理");
        return db.one("select id,parent_id from hx_agent where shop_id=? and id=?",shop,id);
    }

    private static int pageSize(int requested){return Math.max(1,Math.min(requested,50));}

    private Map<String,Object> page(List<Map<String,Object>> rows,int size,long shop){
        boolean more=rows.size()>size;
        if(more)rows=new ArrayList<>(rows.subList(0,size));
        List<Map<String,Object>> items=new ArrayList<>();
        for(Map<String,Object> row:rows)items.add(node(row));
        addRegions(shop,items);
        return map("items",items,"nextCursor",more?number(items.get(items.size()-1),"id"):null);
    }

    private Map<String,Object> node(Map<String,Object> row){
        Map<String,Object> result=new LinkedHashMap<>(row);
        result.remove("child_count");
        result.remove("customer_count");
        result.put("childCount",number(row,"child_count"));
        result.put("customerCount",number(row,"customer_count"));
        return result;
    }

    private void addRegions(long shop,List<Map<String,Object>> rows){
        if(rows.isEmpty())return;
        StringJoiner slots=new StringJoiner(",");
        List<Object> args=new ArrayList<>();
        args.add(shop);
        Map<Long,List<String>> byAgent=new HashMap<>();
        for(Map<String,Object> row:rows){slots.add("?");args.add(number(row,"id"));byAgent.put(number(row,"id"),new ArrayList<>());}
        for(Map<String,Object> area:db.jdbc().queryForList("select agent_id,region_path from hx_agent_region where shop_id=? and agent_id in ("+slots+") order by region_path",args.toArray()))
            byAgent.get(number(area,"agent_id")).add(str(area,"region_path"));
        for(Map<String,Object> row:rows)row.put("regions",byAgent.get(number(row,"id")));
    }

    private static final String NODE_COLUMNS="select a.id,a.member_id,a.parent_id,a.shop_id,a.rank_no,a.status,a.approved_at,m.name,"
        +"(select count(*) from hx_agent c where c.shop_id=a.shop_id and c.parent_id=a.id) child_count,"
        +"(select count(*) from hx_binding b where b.shop_id=a.shop_id and b.agent_id=a.id) customer_count "
        +"from hx_agent a join hx_member m on m.id=a.member_id ";

    public Map<String,Object> children(Actor actor,long shop,long parentId,long afterId,int requested){
        canRead(actor,shop);
        return listChildren(shop,parentId,afterId,requested);
    }

    public Map<String,Object> ownChildren(Actor actor,long shop,long parentId,long afterId,int requested){
        requireTeamAgent(actor,shop,parentId);
        return listChildren(shop,parentId,afterId,requested);
    }

    private Map<String,Object> listChildren(long shop,long parentId,long afterId,int requested){
        require(parentId>=0&&afterId>=0,"代理编号或游标无效");
        if(parentId>0)agent(shop,parentId);
        int size=pageSize(requested);
        String condition=parentId==0
            ?"not exists(select 1 from hx_agent p where p.id=a.parent_id and p.shop_id=a.shop_id)"
            :"a.parent_id=?";
        List<Map<String,Object>> rows=parentId==0
            ?db.jdbc().queryForList(NODE_COLUMNS+"where a.shop_id=? and "+condition+" and a.id>? order by a.id limit ?",shop,afterId,size+1)
            :db.jdbc().queryForList(NODE_COLUMNS+"where a.shop_id=? and "+condition+" and a.id>? order by a.id limit ?",shop,parentId,afterId,size+1);
        return page(rows,size,shop);
    }

    public Map<String,Object> search(Actor actor,long shop,String query,String region,long afterId,int requested){
        canRead(actor,shop);
        require(afterId>=0,"游标无效");
        String term=query==null?"":query.trim(),area=region==null?"":region.trim();
        require(term.length()<=80&&area.length()<=100,"筛选条件过长");
        if(!area.isEmpty())require(HexuRegions.valid(area),"区域无效");
        int size=pageSize(requested);
        StringBuilder sql=new StringBuilder(NODE_COLUMNS+"where a.shop_id=? and a.id>?");
        List<Object> args=new ArrayList<>(Arrays.asList(shop,afterId));
        if(!term.isEmpty()){
            long id=-1;
            try{id=Long.parseLong(term);}catch(NumberFormatException ignored){}
            sql.append(" and (m.name like ? or a.id=?)");
            args.add("%"+term+"%");args.add(id);
        }
        if(!area.isEmpty()){
            sql.append(" and exists(select 1 from hx_agent_region r where r.shop_id=a.shop_id and r.agent_id=a.id and r.region_path=?)");
            args.add(area);
        }
        sql.append(" order by a.id limit ?");args.add(size+1);
        return page(db.jdbc().queryForList(sql.toString(),args.toArray()),size,shop);
    }

    public Map<String,Object> detail(Actor actor,long shop,long id){
        canRead(actor,shop);
        return readDetail(shop,id);
    }

    public Map<String,Object> ownDetail(Actor actor,long shop,long id){
        requireTeamAgent(actor,shop,id);
        return readDetail(shop,id);
    }

    private void requireTeamAgent(Actor actor,long shop,long id){
        Map<String,Object> self=db.agent(actor.id,shop);
        require(self!=null&&id>0,"您尚未取得本店代理身份");
        long selfId=number(self,"id"),current=id;
        Set<Long> seen=new HashSet<>();
        while(current>0&&seen.add(current)&&seen.size()<=100){
            if(current==selfId)return;
            Map<String,Object> node=db.optional("select parent_id from hx_agent where shop_id=? and id=?",shop,current);
            current=node==null||node.get("parent_id")==null?0:number(node,"parent_id");
        }
        throw new BusinessException("只能查看本人及下级代理");
    }

    private Map<String,Object> readDetail(long shop,long id){
        agent(shop,id);
        Map<String,Object> node=node(db.one(NODE_COLUMNS+"where a.shop_id=? and a.id=?",shop,id));
        addRegions(shop,Collections.singletonList(node));
        List<Map<String,Object>> ancestors=new ArrayList<>();
        Set<Long> seen=new HashSet<>();
        long current=id;
        while(current>0){
            require(seen.add(current)&&seen.size()<=100,"代理关系存在循环或层级过深");
            Map<String,Object> item=db.optional("select a.id,a.parent_id,a.rank_no,a.status,m.name from hx_agent a join hx_member m on m.id=a.member_id where a.shop_id=? and a.id=?",shop,current);
            if(item==null)break;
            ancestors.add(item);
            current=item.get("parent_id")==null?0:number(item,"parent_id");
        }
        Collections.reverse(ancestors);
        Map<String,Object> root=ancestors.get(0);
        if(root.get("parent_id")!=null){
            long parentId=number(root,"parent_id");
            Map<String,Object> parent=db.optional("select a.id,a.member_id,a.shop_id,m.name from hx_agent a join hx_member m on m.id=a.member_id where a.id=?",parentId);
            if(parent!=null&&number(parent,"shop_id")!=shop){
                for(Map<String,Object> document:db.jdbc().queryForList("select body_json from hx_document where shop_id=? and member_id=? and kind='peer_authorization' and status='ACTIVE'",shop,number(parent,"member_id"))){
                    Map<String,Object> authorization=db.parse(document.get("body_json"));
                    if(number(authorization,"parentAgentId")==parentId&&number(authorization,"childAgentId")==number(root,"id")){
                        node.put("externalParent",map("id",parentId,"name",str(parent,"name")));
                        break;
                    }
                }
            }
        }
        Map<Long,List<Long>> children=new HashMap<>();
        for(Map<String,Object> item:db.jdbc().queryForList("select id,parent_id from hx_agent where shop_id=?",shop))
            if(item.get("parent_id")!=null)children.computeIfAbsent(number(item,"parent_id"),key->new ArrayList<>()).add(number(item,"id"));
        Set<Long> descendants=new HashSet<>();
        Deque<Long> pending=new ArrayDeque<>(children.getOrDefault(id,Collections.emptyList()));
        while(!pending.isEmpty()){
            long next=pending.removeFirst();
            require(next!=id&&descendants.add(next)&&descendants.size()<=10000,"代理关系存在循环或团队过大");
            pending.addAll(children.getOrDefault(next,Collections.emptyList()));
        }
        node.put("ancestors",ancestors);
        node.put("descendantCount",descendants.size());
        return node;
    }

    public List<Map<String,Object>> regions(Actor actor,long shop){
        canRead(actor,shop);
        List<Map<String,Object>> groups=new ArrayList<>();
        for(Map<String,Object> row:db.jdbc().queryForList("select region_path,count(*) agent_count from hx_agent_region where shop_id=? group by region_path order by region_path",shop))
            groups.add(map("region",str(row,"region_path"),"agentCount",number(row,"agent_count")));
        return groups;
    }

    public List<String> regionOptions(Actor actor,long shop,String query){
        canRead(actor,shop);
        return HexuRegions.options(query);
    }

    @Transactional
    public Map<String,Object> setRegions(Actor actor,long shop,long id,List<?> requested,String reason){
        db.manage(actor,shop,"OWNER");
        agent(shop,id);
        require(requested!=null&&requested.size()<=10,"最多设置10个经营区域");
        require(reason!=null&&reason.trim().length()>=2&&reason.trim().length()<=200,"请填写区域调整原因");
        SortedSet<String> areas=new TreeSet<>();
        for(Object value:requested){
            require(value instanceof String,"区域格式无效");
            String area=HexuRegions.canonical((String)value);
            require(area.length()<=100&&HexuRegions.valid(area),"请选择有效的省、市、区县");
            require(areas.add(area),"经营区域重复");
        }
        List<String> before=db.jdbc().queryForList("select region_path from hx_agent_region where shop_id=? and agent_id=? order by region_path",String.class,shop,id);
        db.jdbc().update("delete from hx_agent_region where shop_id=? and agent_id=?",shop,id);
        for(String area:areas)db.jdbc().update("insert into hx_agent_region(agent_id,shop_id,region_path,created_by) values(?,?,?,?)",id,shop,area,actor.id);
        db.audit(actor,shop,"AGENT_REGION_SET",String.valueOf(id),map("regions",before),map("regions",areas,"reason",reason.trim()));
        return detail(actor,shop,id);
    }
}
