package com.ruoyi.hexu;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.ruoyi.hexu.BusinessException.require;
@Repository
public class HexuStore {
    private final JdbcTemplate jdbc; public JdbcTemplate jdbc(){return jdbc;} private final ObjectMapper json;
    public HexuStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Map<String,Object> one(String sql,Object... args){List<Map<String,Object>> list=jdbc.queryForList(sql,args);require(!list.isEmpty(),"记录不存在");return list.get(0);}
    public Map<String,Object> optional(String sql,Object... args){List<Map<String,Object>> list=jdbc.queryForList(sql,args);return list.isEmpty()?null:list.get(0);}
    public long insert(String sql,Object... args){GeneratedKeyHolder keys=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},keys);return Objects.requireNonNull(keys.getKey()).longValue();}
    public String json(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new BusinessException("数据序列化失败");}}
    @SuppressWarnings("unchecked") public Map<String,Object> parse(Object value){try{return json.readValue(String.valueOf(value),Map.class);}catch(Exception e){throw new BusinessException("数据格式错误");}}
    public static long number(Map<String,Object> map,String key){Object value=map.get(key);if(value==null)return 0;try{return new java.math.BigDecimal(value.toString()).longValueExact();}catch(Exception e){throw new BusinessException(key+"必须为整数");}}
    public static String str(Map<String,Object> map,String key){Object v=map.get(key);return v==null?"":v.toString();}
    public static boolean bool(Map<String,Object> map,String key){return Boolean.TRUE.equals(map.get(key))||"1".equals(str(map,key))||"true".equalsIgnoreCase(str(map,key));}
    public static Map<String,Object> map(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put(kv[i].toString(),kv[i+1]);return m;}
    public static String id(String prefix){return prefix+UUID.randomUUID().toString().replace("-","");}
    public Map<String,Object> shop(long id){Map<String,Object> s=one("select * from hx_shop where id=?",id);require("ACTIVE".equals(str(s,"status")),"商城当前不可用");return s;}
    public void member(Actor a){require(!a.staff,"请使用会员身份操作");Map<String,Object> m=one("select * from hx_member where id=?",a.id);require("ACTIVE".equals(str(m,"status")),"账户不可用");}
    public void manage(Actor a,long shop,String... roles){if(a.platform)return;Map<String,Object> s=one("select * from hx_shop where id=?",shop);if(number(s,"owner_id")==a.id&&!a.staff)return;require(a.staff,"没有商城管理权限");Map<String,Object> staff=optional("select * from hx_staff where user_id=? and shop_id=?",a.id,shop);require(staff!=null,"无权访问其他商城");String role=str(staff,"role_code");require("OWNER".equals(role)||Arrays.asList(roles).contains(role),"该岗位无此操作权限");}
    public void platform(Actor a){require(a.platform,"仅平台管理员可以操作");}
    public Map<String,Object> agent(long member,long shop){return optional("select * from hx_agent where member_id=? and shop_id=? and status='ACTIVE'",member,shop);}
    public Map<String,Object> config(long shop,String kind){Map<String,Object>d=optional("select body_json from hx_document where shop_id=? and kind=? and status='ACTIVE' order by updated_at desc,id desc limit 1",shop,kind);return d==null?new LinkedHashMap<>():parse(d.get("body_json"));}
    public long parameter(long shop,String kind,String key,long fallback){Map<String,Object> c=config(shop,kind);return c.containsKey(key)?number(c,key):fallback;}
    public void audit(Actor a,long shop,String action,String ref,Object before,Object after){String source="SYSTEM",request="",ip="";org.springframework.web.context.request.RequestAttributes attrs=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();if(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes){javax.servlet.http.HttpServletRequest req=((org.springframework.web.context.request.ServletRequestAttributes)attrs).getRequest();source=req.getRequestURI().contains("/admin/")?"ADMIN":"APP";String raw=req.getHeader("Idempotency-Key");if(raw!=null)request=raw.substring(0,Math.min(100,raw.length()));String remote=req.getRemoteAddr();if(remote!=null)ip=remote.contains(":")?remote.substring(0,Math.min(8,remote.length()))+"::*":remote.replaceFirst("[0-9]+$","*");}jdbc.update("insert into hx_audit(shop_id,actor_id,action,reference_id,before_json,after_json,actor_kind,source,request_id,ip_masked) values(?,?,?,?,?,?,?,?,?,?)",shop,a.id,action,ref,json(before),json(after),a.id==0?"SYSTEM":a.staff?"STAFF":"MEMBER",source,request,ip);}
    public void message(long shop,long member,String title,String ref){new HexuNotifications(this).emit(shop,member,HexuNotifications.classify(title),title,ref);}
    public void defective(long shop,String sku,int qty,String reason,String ref){require(qty>=0,"残次入库数量不能为负数");if(qty==0)return;require(jdbc.update("update hx_sku set defective=defective+? where shop_id=? and id=?",qty,shop,sku)==1,"残次商品不存在");jdbc.update("insert into hx_inventory_log(shop_id,sku_id,available_delta,locked_delta,defective_delta,reason,reference_id) values(?,?,0,0,?,?,?)",shop,sku,qty,reason,ref);}
    public void inventory(long shop,String sku,int available,int locked,String reason,String ref){int n=jdbc.update("update hx_sku set available=available+?,locked=locked+? where shop_id=? and id=? and available+?>=0 and locked+?>=0",available,locked,shop,sku,available,locked);require(n==1,"库存不足或库存锁定已失效");jdbc.update("insert into hx_inventory_log(shop_id,sku_id,available_delta,locked_delta,reason,reference_id) values(?,?,?,?,?,?)",shop,sku,available,locked,reason,ref);if(available<0){Map<String,Object>item=one("select k.available,k.warning_qty,k.name,s.owner_id from hx_sku k join hx_shop s on s.id=k.shop_id where k.shop_id=? and k.id=?",shop,sku);if(number(item,"available")<=number(item,"warning_qty")&&number(item,"available")-available>number(item,"warning_qty"))new HexuNotifications(this).emit(shop,number(item,"owner_id"),"INVENTORY_LOW",str(item,"name")+" 可售库存低于预警值",sku);}}
    public void ensureWallet(long shop,long member){
        jdbc.update("insert into hx_wallet(shop_id,member_id) values(?,?) on duplicate key update shop_id=shop_id",shop,member);
    }
    public void ledger(long shop,long member,long amount,String kind,String reference){ensureWallet(shop,member);jdbc.update("update hx_wallet set available=available+? where shop_id=? and member_id=?",amount,shop,member);jdbc.update("insert into hx_ledger(shop_id,member_id,category,amount,reference_id) values(?,?,?,?,?)",shop,member,kind,amount,reference);}
    public void ensurePoints(long shop,long member){
        // An INSERT ... SELECT NOT EXISTS takes a shared lock on an existing row.
        // Parallel account reads then deadlock while upgrading that lock in refresh().
        jdbc.update("insert into hx_points(shop_id,member_id) values(?,?) on duplicate key update shop_id=shop_id",shop,member);
    }
    @org.springframework.transaction.annotation.Transactional public void points(long shop,long member,long delta,String kind,String ref,boolean reclaim){new HexuPointBatches(this).mutate(shop,member,delta,kind,ref,reclaim,null);}
    @org.springframework.transaction.annotation.Transactional public void awardPoints(long shop,long member,long amount,String kind,String ref,long expiryDays){new HexuPointBatches(this).mutate(shop,member,amount,kind,ref,false,expiryDays);}
    @org.springframework.transaction.annotation.Transactional public Map<String,Object> pointAccount(long shop,long member){return new HexuPointBatches(this).refresh(shop,member);}
    public Map<String,Object> pointSnapshot(long shop,long member){return new HexuPointBatches(this).snapshot(shop,member);}
    public List<SettlementCalculator.Node> chain(long start,long shop){List<SettlementCalculator.Node> nodes=new ArrayList<>();Set<Long> seen=new HashSet<>();Long next=start;
        while(next!=null&&next!=0){require(seen.add(next)&&nodes.size()<100,"代理关系存在循环或层级过深");Map<String,Object> n=one("select * from hx_agent where id=?",next);require("ACTIVE".equals(str(n,"status")),"收益链含无效代理");boolean external=number(n,"shop_id")!=shop;
            if(external){boolean approved=false;for(Map<String,Object>d:jdbc.queryForList("select body_json from hx_document where shop_id=? and member_id=? and kind='peer_authorization' and status='ACTIVE'",shop,number(n,"member_id"))){Map<String,Object>b=parse(d.get("body_json"));if(!nodes.isEmpty()&&number(b,"parentAgentId")==next&&number(b,"childAgentId")==nodes.get(nodes.size()-1).agentId&&number(n,"rank_no")==nodes.get(nodes.size()-1).rank)approved=true;}require(approved,"跨商城收益链尚未获批");}
            nodes.add(new SettlementCalculator.Node(number(n,"id"),number(n,"member_id"),(int)number(n,"rank_no")));if(external)break;next=n.get("parent_id")==null?null:number(n,"parent_id");}return nodes;
    }
}
