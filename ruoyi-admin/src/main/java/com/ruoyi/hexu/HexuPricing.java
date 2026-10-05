package com.ruoyi.hexu;

import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Shared by checkout quotes and committed orders; all money stays in integer cents. */
public class HexuPricing {
 private final HexuStore db;
 public HexuPricing(HexuStore db){this.db=db;}
 private Set<String> values(Object raw){
  Set<String> ids=new HashSet<>();
  if(raw==null)return ids;
  Collection<?> entries=raw instanceof Collection?(Collection<?>)raw:Arrays.asList(raw.toString().split(","));
  for(Object entry:entries){
   String value=String.valueOf(entry).trim();
   if(!value.isEmpty()&&!"全部商品".equals(value)&&!"全部用户".equals(value))ids.add(value);
  }
  return ids;
 }
 private List<Integer> eligible(List<Map<String,Object>> lines,Map<String,Object> rule){Set<String> ids=values(rule.get("skuIds"));List<Integer> out=new ArrayList<>();for(int i=0;i<lines.size();i++){Map<String,Object>s=(Map<String,Object>)lines.get(i).get("sku");if(ids.isEmpty()||ids.contains(str(s,"id")))out.add(i);}return out;}
 private long sum(long[] paid,List<Integer> indices){long n=0;for(int i:indices)n=Math.addExact(n,paid[i]);return n;}
 private void allocate(long[] paid,List<Integer> indices,long discount){long total=sum(paid,indices);require(discount>=0&&discount<=total,"优惠金额无效");if(discount==0)return;long accum=0,previous=0;for(int i:indices){accum+=paid[i];long target=SettlementCalculator.proportion(discount,accum,total);paid[i]-=target-previous;previous=target;}}
 private long reduction(Map<String,Object> r,long eligible){String type=str(r,"type");if(eligible<number(r,"threshold"))return 0;if(Arrays.asList("DISCOUNT","FULL_DISCOUNT","LIMITED","FLASH","MEMBER","TIER").contains(type)){long rate=number(r,"rateBps");if("TIER".equals(type)){long threshold=-1;rate=10000;for(Map<String,Object>tier:items(r,"tiers"))if(number(tier,"threshold")<=eligible&&number(tier,"threshold")>threshold){threshold=number(tier,"threshold");rate=number(tier,"rateBps");}}require(rate>0&&rate<=10000,"折扣基点应为1至10000");return eligible-SettlementCalculator.proportion(eligible,rate,10000);}return Math.min(eligible,number(r,"discount"));}
 @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object>r,String key){require(r.get(key) instanceof List,"请配置"+key+"明细");return (List<Map<String,Object>>)r.get(key);}
 private Map<String,Object> combination(Map<String,Object>r,List<Map<String,Object>>lines,long[]paid){
  String type=str(r,"type");List<Integer>affected=new ArrayList<>();long units=Long.MAX_VALUE,gross=0;
  for(Map<String,Object>entry:items(r,"items")){int found=-1;for(int i=0;i<lines.size();i++)if(str((Map<String,Object>)lines.get(i).get("sku"),"id").equals(str(entry,"skuId")))found=i;
   if(found<0)return map("discount",0);affected.add(found);units=Math.min(units,number(lines.get(found),"qty")/number(entry,"qty"));}
  if(units==0||units==Long.MAX_VALUE)return map("discount",0);
  if(!"BUNDLE".equals(type)){long qualifying=0;Set<String>qualifiers=values(r.get("qualifierSkuIds"));for(int i=0;i<lines.size();i++)if(!affected.contains(i)&&(qualifiers.isEmpty()||qualifiers.contains(str((Map<String,Object>)lines.get(i).get("sku"),"id"))))qualifying+=paid[i];if(qualifying<=0||qualifying<number(r,"threshold"))return map("discount",0);units=Math.min(units,r.containsKey("maxSetsPerOrder")?number(r,"maxSetsPerOrder"):1);}
  for(Map<String,Object>entry:items(r,"items"))for(int i:affected)if(str((Map<String,Object>)lines.get(i).get("sku"),"id").equals(str(entry,"skuId")))gross+=SettlementCalculator.proportion(paid[i],Math.multiplyExact(number(entry,"qty"),units),number(lines.get(i),"qty"));
  long price="GIFT".equals(type)?0:Math.multiplyExact(number(r,"price"),units);long discount=Math.max(0,gross-price);
  // A gift's promotional value is allocated across the qualifying basket for cost and refund accounting.
  if(!"BUNDLE".equals(type)){Set<String>qualifiers=values(r.get("qualifierSkuIds"));for(int i=0;i<lines.size();i++)if(!affected.contains(i)&&(qualifiers.isEmpty()||qualifiers.contains(str((Map<String,Object>)lines.get(i).get("sku"),"id"))))affected.add(i);}
  return map("discount",discount,"qty",units,"indices",affected);
 }
 void audience(Actor a,long shop,Map<String,Object>r){Set<String> members=values(r.get("memberIds"));require(members.isEmpty()||members.contains(String.valueOf(a.id)),"不在活动指定人群内");if("NEWCOMER".equals(str(r,"type")))require(db.jdbc().queryForObject("select count(*) from hx_order where shop_id=? and buyer_id=? and status not in ('UNPAID','CANCELLED')",Long.class,shop,a.id)==0,"新人券仅限本商城首单使用");if("MEMBER".equals(str(r,"type")))require(db.agent(a.id,shop)!=null,"会员价格仅适用有效代理身份");}
 public Map<String,Object> calculate(Actor a,long shop,List<Map<String,Object>>lines,Map<String,Object>b,boolean wholesale,boolean lock){
  long[] paid=new long[lines.size()];List<Integer>all=new ArrayList<>();long subtotal=0;for(int i=0;i<lines.size();i++){paid[i]=number(lines.get(i),"amount");subtotal=Math.addExact(subtotal,paid[i]);all.add(i);}
  Map<String,Object>groupPlan=new HexuGroupPurchase(db).plan(a,shop,b,lock);if(!groupPlan.isEmpty()){require(!wholesale&&paid.length==1,"拼团不适用批发采购");long price=number(groupPlan,"price");require(price<=subtotal,"拼团价已高于当前零售价，请重新配置活动");return map("subtotal",subtotal,"discount",subtotal-price,"couponDiscount",0,"promotionDiscount",subtotal-price,"pointsUsed",0,"pointsDiscount",0,"pointsScope",0,"pointsPerYuan",100,"deductionPercent",0,"freight",0,"productPaid",price,"total",price,"paidLines",new long[]{price},"coupon",null,"promotions",Collections.emptyList());}
  List<Map<String,Object>> applied=new ArrayList<>();boolean couponAllowed=true,pointsAllowed=true;long promotionDiscount=0;
  if(!wholesale){List<Map<String,Object>>campaigns=db.jdbc().queryForList("select * from hx_document where shop_id=? and kind in ('promotion_rule','bundle') and status='ACTIVE' order by created_at,id"+(lock?" for update":""),shop);campaigns.sort(Comparator.comparingLong(d->number(db.parse(d.get("body_json")),"priority")));Set<String>exclusiveGroups=new HashSet<>();for(Map<String,Object>d:campaigns){
   Map<String,Object>r=db.parse(d.get("body_json"));if(number(r,"startsAt")>System.currentTimeMillis()||number(r,"expiresAt")<=System.currentTimeMillis())continue;
   try{audience(a,shop,r);}catch(BusinessException ignored){continue;}
   String group=str(r,"exclusiveGroup");if(!group.isEmpty()&&exclusiveGroups.contains(group))continue;
   List<Integer>eligible=eligible(lines,r);long amount=sum(paid,eligible);long discount;long qty=0;
   if("bundle".equals(str(d,"kind"))){Map<String,Object>combo=combination(r,lines,paid);discount=number(combo,"discount");if(discount==0)continue;eligible=(List<Integer>)combo.get("indices");qty=number(combo,"qty");}
   else{discount=reduction(r,amount);for(int i:eligible)qty+=number(lines.get(i),"qty");}if(discount==0)continue;
   long used=db.jdbc().queryForObject("select coalesce(sum(u.qty),0) from hx_marketing_usage u join hx_order o on o.id=u.order_id where u.campaign_id=? and o.status<>'CANCELLED'",Long.class,d.get("id"));
   long own=db.jdbc().queryForObject("select coalesce(sum(u.qty),0) from hx_marketing_usage u join hx_order o on o.id=u.order_id where u.campaign_id=? and o.buyer_id=? and o.status<>'CANCELLED'",Long.class,d.get("id"),a.id);
   require(number(r,"quantity")==0||used+qty<=number(r,"quantity"),"活动名额不足");require(number(r,"perMember")==0||own+qty<=number(r,"perMember"),"超过活动每人限购数量");
   allocate(paid,eligible,discount);promotionDiscount+=discount;applied.add(map("id",d.get("id"),"body",r,"qty",qty,"discount",discount));couponAllowed=couponAllowed&&bool(r,"stackCoupon");pointsAllowed=pointsAllowed&&(!r.containsKey("stackPoints")||bool(r,"stackPoints"));exclusiveGroups.add(group);if(!bool(r,"stackPromotions"))break;
  }}
  Map<String,Object> coupon=null;long couponDiscount=0;String couponId=str(b,"couponId");
  if(!couponId.isEmpty()){require(!wholesale&&couponAllowed,"当前活动不能叠加优惠券");coupon=db.one("select * from hx_document where id=? and shop_id=? and member_id=? and kind='coupon_claim' and status='AVAILABLE'"+(lock?" for update":""),couponId,shop,a.id);Map<String,Object>c=db.parse(coupon.get("body_json"));require(number(c,"expiresAt")>System.currentTimeMillis(),"优惠券已过期");audience(a,shop,c);List<Integer>indices=eligible(lines,c);long base=sum(paid,indices);require(base>0&&base>=number(c,"threshold"),"未满足指定商品用券门槛");couponDiscount=reduction(c,base);allocate(paid,indices,couponDiscount);}
  long discount=promotionDiscount+couponDiscount,points=number(b,"points"),scope=number(b,"pointsScope");require(scope==0||scope==shop,"商城积分不能跨店抵扣");require(points>=0&&(!wholesale&&pointsAllowed||points==0),"当前订单不可抵扣积分");long rate=db.parameter(scope,"points_rule","pointsPerYuan",100);require(rate>0&&rate<=100000,"积分抵扣比例未配置");long cash=Math.multiplyExact(points,100)/rate;require(points==0||cash>0,"积分不足以抵扣一分钱");long cap=db.parameter(shop,"points_rule","deductionPercent",20);require(cash<=SettlementCalculator.proportion(subtotal-discount,cap,100),"积分抵扣超过活动允许的比例");Map<String,Object>account=db.pointSnapshot(scope,a.id);require(points==0||account!=null&&!bool(account,"frozen")&&number(account,"available")>=points,"积分余额不足或账户已冻结");long[] freightPaid=paid.clone();allocate(paid,all,cash);
  long freight=new HexuFreight(db).calculate(shop,lines,freightPaid,b.get("address") instanceof Map?(Map<String,Object>)b.get("address"):Collections.emptyMap());long productPaid=subtotal-discount-cash;require(productPaid>0,"商品实付必须大于零");
  return map("subtotal",subtotal,"discount",discount,"couponDiscount",couponDiscount,"promotionDiscount",promotionDiscount,"pointsUsed",points,"pointsDiscount",cash,"pointsScope",scope,"pointsPerYuan",rate,"deductionPercent",cap,"freight",freight,"productPaid",productPaid,"total",productPaid+freight,"paidLines",paid,"coupon",coupon,"promotions",applied);
 }
 public static void validate(String kind,Map<String,Object>b){
  if(Arrays.asList("promotion_rule","bundle").contains(kind))require(!str(b,"name").trim().isEmpty()&&str(b,"name").trim().length()<=80,"活动名称须为1至80字");
  if(Arrays.asList("promotion_rule","bundle").contains(kind))for(String key:Arrays.asList("price","threshold","discount","rateBps","quantity","perMember","startsAt","expiresAt"))require(!b.containsKey(key)||b.get(key)!=null,key+"不能为null，请填写有效数字（明确0限量表示不限）");
  require(!b.containsKey("priority")||number(b,"priority")>=0&&number(b,"priority")<=100000,"活动优先级须为0至100000");require(!b.containsKey("maxSetsPerOrder")||number(b,"maxSetsPerOrder")>0&&number(b,"maxSetsPerOrder")<=1000,"每单套数须为1至1000");
  if("bundle".equals(kind)){require(Arrays.asList("BUNDLE","ADDON","GIFT","EXCHANGE").contains(str(b,"type")),"请选择套餐、加价购、满赠或换购类型");List<Map<String,Object>>list=items(b,"items");require(!list.isEmpty()&&list.size()<=20,"组合商品须为1至20项");Set<String>seen=new HashSet<>();for(Map<String,Object>i:list)require(!str(i,"skuId").isEmpty()&&seen.add(str(i,"skuId"))&&number(i,"qty")>0&&number(i,"qty")<=1000,"组合商品编号和数量无效");require(number(b,"price")>=0&&("GIFT".equals(str(b,"type"))||number(b,"price")>0),"套餐或换购价格须大于零");require(number(b,"threshold")>=0&&number(b,"startsAt")<number(b,"expiresAt")&&number(b,"expiresAt")>System.currentTimeMillis(),"组合门槛或有效时间不合法");require(number(b,"quantity")>=0&&number(b,"perMember")>=0,"组合限量不能为负数");}
  if("TIER".equals(str(b,"type"))){List<Map<String,Object>>tiers=items(b,"tiers");require(!tiers.isEmpty()&&tiers.size()<=20,"请配置1至20档阶梯价");Set<Long>thresholds=new HashSet<>();for(Map<String,Object>tier:tiers)require(number(tier,"threshold")>=0&&thresholds.add(number(tier,"threshold"))&&number(tier,"rateBps")>0&&number(tier,"rateBps")<10000,"阶梯门槛不能重复且折扣必须有效");}
  if("coupon".equals(kind)){if(b.containsKey("name"))require(!str(b,"name").trim().isEmpty()&&str(b,"name").trim().length()<=80,"优惠券名称须为1至80字");String type=str(b,"type");require(type.isEmpty()||Arrays.asList("FULL_REDUCTION","DISCOUNT","PRODUCT","NEWCOMER","TARGETED").contains(type),"优惠券类型无效");if("DISCOUNT".equals(type))require(number(b,"rateBps")>0&&number(b,"rateBps")<10000,"折扣率须在0至10000基点之间");else require(number(b,"discount")>0&&number(b,"threshold")>=number(b,"discount"),"优惠金额与门槛不合法");require(number(b,"expiresAt")>System.currentTimeMillis()&&number(b,"quantity")>0,"优惠券期限和数量不合法");}
  if("promotion_rule".equals(kind)){require(Arrays.asList("FULL_REDUCTION","FULL_DISCOUNT","LIMITED","FLASH","MEMBER","TIER").contains(str(b,"type")),"请选择受支持的价格促销类型");require(number(b,"startsAt")<number(b,"expiresAt")&&number(b,"expiresAt")>System.currentTimeMillis(),"活动时间无效");require(number(b,"threshold")>=0&&number(b,"quantity")>=0&&number(b,"perMember")>=0,"活动门槛或限购参数不能为负数");if("FULL_REDUCTION".equals(str(b,"type")))require(number(b,"discount")>0&&number(b,"threshold")>=number(b,"discount"),"满减金额无效");else if(!"TIER".equals(str(b,"type")))require(number(b,"rateBps")>0&&number(b,"rateBps")<10000,"折扣率须在0至10000基点之间");}
 }
}
