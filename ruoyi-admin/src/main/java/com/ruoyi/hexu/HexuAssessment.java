package com.ruoyi.hexu;
import java.util.*;
import java.sql.Timestamp;
import java.time.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Assessment uses completed order snapshots, so later customer rebinds do not rewrite sales. */
public class HexuAssessment {
 private final HexuStore db;
 public HexuAssessment(HexuStore db){this.db=db;}
 public static void validate(Map<String,Object>b){validatePeriod(b);require(number(b,"periodMonths")>=1&&number(b,"periodMonths")<=24,"考核周期须为1至24个月");require(number(b,"salesThreshold")>=0&&number(b,"directCount")>=0&&number(b,"repeatRate")>=0&&number(b,"repeatRate")<=100,"考核阈值不合法");require(Arrays.asList("AND","OR","WEIGHTED").contains(str(b,"operator")),"指标关系须为AND、OR或WEIGHTED");require(!b.containsKey("teamDepth")||number(b,"teamDepth")>=1&&number(b,"teamDepth")<=100,"团队深度须为1至100");}
 public static void validatePeriod(Map<String,Object>b){
  String kind=str(b,"periodKind");require(kind.isEmpty()||Arrays.asList("ROLLING","MONTH","QUARTER","YEAR","CUSTOM").contains(kind),"考核周期类型无效");
  if("CUSTOM".equals(kind)){LocalDate start=date(b,"startDate"),end=date(b,"endDate");require(end.isAfter(start)&&end.isBefore(start.plusYears(5)),"自定义考核日期须递增且不超过五年");}
  if("WEIGHTED".equals(str(b,"operator"))){long sum=0;for(String key:Arrays.asList("sales","people","repeat")){long weight=number(b,key+"Weight");require(weight>=0&&weight<=100,"指标权重须为0至100");if(!b.containsKey(key+"Enabled")||bool(b,key+"Enabled"))sum+=weight;}require(sum==100&&number(b,"scoreThreshold")>0&&number(b,"scoreThreshold")<=100,"启用指标权重之和须为100，并配置达标分数");}
  require(!b.containsKey("repeatMinOrders")||number(b,"repeatMinOrders")>=2&&number(b,"repeatMinOrders")<=100,"复购订单门槛须为2至100");require(!b.containsKey("repeatMinAmount")||number(b,"repeatMinAmount")>=0,"有效订单金额不能为负数");
 }
 private static LocalDate date(Map<String,Object>b,String key){try{return LocalDate.parse(str(b,key));}catch(Exception e){throw new BusinessException("请填写有效的"+key+"日期（YYYY-MM-DD）");}}
 public static LocalDate[] window(Map<String,Object>c,LocalDate today){long months=c.containsKey("periodMonths")?number(c,"periodMonths"):3;String kind=str(c,"periodKind");LocalDate start=today.minusMonths(months),end=today.plusDays(1);if("MONTH".equals(kind))start=today.withDayOfMonth(1);if("QUARTER".equals(kind))start=LocalDate.of(today.getYear(),((today.getMonthValue()-1)/3)*3+1,1);if("YEAR".equals(kind))start=today.withDayOfYear(1);if("CUSTOM".equals(kind)){start=date(c,"startDate");end=date(c,"endDate");}return new LocalDate[]{start,end};}
 public Map<String,Object> evaluate(long member,long shop){
  Map<String,Object>config=db.config(shop,"assessment_rule");LocalDate[]w=window(config,LocalDate.now(ZoneOffset.UTC));return evaluateWindow(member,shop,config,w[0],w[1]);
 }
 public Map<String,Object> evaluateWindow(long member,long shop,Map<String,Object>config,LocalDate from,LocalDate until){
  Map<String,Object>agent=db.agent(member,shop);require(agent!=null,"本商城代理身份不存在");long id=number(agent,"id");boolean configured=!config.isEmpty();Timestamp start=Timestamp.valueOf(from.atStartOfDay()),end=Timestamp.valueOf(until.atStartOfDay());
  long depth=config.containsKey("teamDepth")?number(config,"teamDepth"):100;boolean includeSelf=!config.containsKey("includeSelf")||bool(config,"includeSelf");long sales=0;Set<String>orders=new HashSet<>();Map<Long,Set<String>>buyers=new HashMap<>();Map<String,Long>orderNets=new HashMap<>(),orderBuyers=new HashMap<>();
  for(Map<String,Object>line:db.jdbc().queryForList("select l.*,o.buyer_id,o.order_type from hx_order_line l join hx_order o on o.id=l.order_id where o.shop_id=? and o.status in ('COMPLETED','REFUNDED') and o.completed_at>=? and o.completed_at<? and o.order_type not in ('POINTS','WHOLESALE','DIRECT_SHIP')",shop,start,end)){
   long net=number(line,"paid")-number(line,"refunded_paid");if(net<=0)continue;Object raw=db.parse(line.get("snapshot_json")).get("chain");if(!(raw instanceof List))continue;List<?>chain=(List<?>)raw;boolean found=false;for(int i=0;i<chain.size()&&i<=depth;i++)if(number((Map<String,Object>)chain.get(i),"agentId")==id&&(includeSelf||i>0))found=true;if(!found)continue;sales=Math.addExact(sales,net);orders.add(str(line,"order_id"));orderNets.merge(str(line,"order_id"),net,Long::sum);orderBuyers.put(str(line,"order_id"),number(line,"buyer_id"));
  }
  for(Map.Entry<String,Long>o:orderNets.entrySet())if(o.getValue()>=number(config,"repeatMinAmount"))buyers.computeIfAbsent(orderBuyers.get(o.getKey()),k->new HashSet<>()).add(o.getKey());long repeatMin=config.containsKey("repeatMinOrders")?number(config,"repeatMinOrders"):2;long repeat=buyers.values().stream().filter(v->v.size()>=repeatMin).count();long repeatBps=buyers.isEmpty()?0:SettlementCalculator.proportion(repeat,10000,buyers.size());
  List<Map<String,Object>>members=db.jdbc().queryForList("select * from hx_agent where shop_id=? and status='ACTIVE' and approved_at<?",shop,end);Set<Long>team=new HashSet<>(),front=new HashSet<>();front.add(id);long direct=0;for(int level=1;level<=depth&&!front.isEmpty();level++){Set<Long>next=new HashSet<>();for(Map<String,Object>m:members)if(front.contains(number(m,"parent_id"))&&team.add(number(m,"id"))){next.add(number(m,"id"));if(level==1)direct++;}front=next;}
  List<Map<String,Object>>metrics=new ArrayList<>();metrics.add(metric("sales","团队销售额",sales,number(config,"salesThreshold"),config));metrics.add(metric("people","有效下级人数",bool(config,"teamCount")?team.size():direct,number(config,"directCount"),config));metrics.add(metric("repeat","客户复购率",repeatBps,number(config,"repeatRate")*100,config));
  long enabled=metrics.stream().filter(m->bool(m,"enabled")).count(),passed=metrics.stream().filter(m->bool(m,"enabled")&&bool(m,"passed")).count();long score=0;for(Map<String,Object>m:metrics)if(bool(m,"enabled")){long threshold=number(m,"threshold");long ratio=threshold==0?10000:Math.min(10000,SettlementCalculator.proportion(number(m,"value"),10000,threshold));score+=SettlementCalculator.proportion(ratio,number(config,str(m,"key")+"Weight"),100);}boolean qualified="WEIGHTED".equals(str(config,"operator"))?score>=number(config,"scoreThreshold")*100:"OR".equals(str(config,"operator"))?passed>0:passed==enabled;boolean eligible=configured&&enabled>0&&qualified&&number(agent,"rank_no")<3;
  if(config.containsKey("effectiveAt")&&number(config,"effectiveAt")>System.currentTimeMillis())eligible=false;
  return map("configured",configured,"qualified",configured&&enabled>0&&qualified,"eligible",eligible,"rank",agent.get("rank_no"),"targetRank",Math.min(3,number(agent,"rank_no")+1),"periodStart",start.toString(),"periodEnd",end.toString(),"sales",sales,"orders",orders.size(),"customers",buyers.size(),"repeatBps",repeatBps,"direct",direct,"team",team.size(),"metrics",metrics,"scoreBps",score,"config",config);
 }
 private Map<String,Object> metric(String key,String label,long value,long threshold,Map<String,Object>c){boolean enabled=!c.containsKey(key+"Enabled")||bool(c,key+"Enabled");return map("key",key,"label",label,"value",value,"threshold",threshold,"enabled",enabled,"weight",number(c,key+"Weight"),"passed",value>=threshold);}
}
