package com.ruoyi.hexu;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** One charge per template, with per-piece/weight increments and explicit destination surcharges. */
public final class HexuFreight {
 private final HexuStore db;public HexuFreight(HexuStore db){this.db=db;}
 public static void validate(Map<String,Object>b){for(String k:Arrays.asList("freightCents","extraFee","remoteFee","freeThreshold"))require(number(b,k)>=0&&number(b,k)<=100000000,"运费金额无效");String mode=str(b,"billing");require(mode.isEmpty()||Arrays.asList("FLAT","COUNT","WEIGHT").contains(mode),"运费计费方式无效");if(!mode.isEmpty()&&!"FLAT".equals(mode))require(number(b,"firstUnits")>0&&number(b,"stepUnits")>0,"首件和续件单位须为正数");require(str(b,"remoteRegions").length()<=1000,"偏远地区配置过长");}
 public long calculate(long shop,List<Map<String,Object>>lines,long[]paid,Map<String,Object>address){Map<String,long[]>quantities=new LinkedHashMap<>();Map<String,Map<String,Object>>templates=new LinkedHashMap<>();for(int i=0;i<lines.size();i++){Map<String,Object>l=lines.get(i),s=(Map<String,Object>)l.get("sku"),d=new HexuProductData(db).detail(shop,str(s,"id"));String ref=str(d,"freight_id");Map<String,Object>r=templates.get(ref);if(r==null){r=ref.isEmpty()?db.config(shop,"freight"):db.parse(db.one("select body_json from hx_document where id=? and shop_id=? and kind='freight' and status='ACTIVE'",ref,shop).get("body_json"));if(r.isEmpty())r=map("freightCents",number(db.parse(db.one("select settings from hx_shop where id=?",shop).get("settings")),"freightCents"));templates.put(ref,r);}long[]q=quantities.computeIfAbsent(ref,k->new long[3]);q[0]=Math.addExact(q[0],number(l,"qty"));q[1]=Math.addExact(q[1],Math.multiplyExact(number(l,"qty"),number(d,"weight_grams")));q[2]=Math.addExact(q[2],paid[i]);}
  long total=0;for(String ref:templates.keySet()){Map<String,Object>r=templates.get(ref);validate(r);long[]q=quantities.get(ref);long base=number(r,"freightCents");String mode=str(r,"billing");if(Arrays.asList("COUNT","WEIGHT").contains(mode)){long units="WEIGHT".equals(mode)?q[1]:q[0],extra=Math.max(0,units-number(r,"firstUnits")),steps=(extra+number(r,"stepUnits")-1)/number(r,"stepUnits");base=Math.addExact(base,Math.multiplyExact(steps,number(r,"extraFee")));}if(number(r,"freeThreshold")>0&&q[2]>=number(r,"freeThreshold"))base=0;
   if(number(r,"remoteFee")>0&&!str(r,"remoteRegions").trim().isEmpty()){require(address!=null&&!str(address,"region").trim().isEmpty(),"请选择配送地区以计算偏远附加运费");for(String region:str(r,"remoteRegions").split("[,，\n]"))if(!region.trim().isEmpty()&&str(address,"region").contains(region.trim())){base=Math.addExact(base,number(r,"remoteFee"));break;}}total=Math.addExact(total,base);
  }return total;
 }
}
