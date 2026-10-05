package com.ruoyi.hexu;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Catalog metadata is scoped to the SKU; settlement continues to use immutable order snapshots. */
public final class HexuProductData {
 private final HexuStore db;
 public HexuProductData(HexuStore db){this.db=db;}
 public Map<String,Object> detail(long shop,String sku){Map<String,Object>d=db.optional("select * from hx_sku_detail where shop_id=? and sku_id=?",shop,sku);if(d==null)d=map("category","","brand","","product_group","","description","","freight_id","","weight_grams",1000,"allow_loose",true,"retail_min",0,"retail_max",0);Object raw=d.remove("gallery_json");d.put("gallery",raw==null?Collections.emptyList():db.parse(raw).get("items"));return d;}
 public void save(Actor actor,long shop,String sku,Map<String,Object>input){Map<String,Object>old=detail(shop,sku),d=new LinkedHashMap<>(old);for(String key:Arrays.asList("category","brand","product_group","description","freight_id","weight_grams","allow_loose","retail_min","retail_max","gallery"))if(input.containsKey(key))d.put(key,input.get(key));
  require(d.get("brand") instanceof String,"品牌须为文本");String brand=HexuText.trim(str(d,"brand"));require(brand.length()<=80&&brand.codePoints().noneMatch(Character::isISOControl),"品牌最多80字且不能包含控制字符");d.put("brand",brand);
  require(str(d,"category").length()<=80&&str(d,"product_group").matches("[A-Za-z0-9_-]{0,64}")&&str(d,"description").length()<=10000,"分类、商品组或描述格式无效");require(number(d,"weight_grams")>0&&number(d,"weight_grams")<=1000000,"单件重量须为1至1000000克");require(number(d,"retail_min")>=0&&number(d,"retail_max")>=0&&(number(d,"retail_max")==0||number(d,"retail_max")>=number(d,"retail_min")),"零售价格允许范围无效");if(!actor.platform){d.put("retail_min",old.get("retail_min"));d.put("retail_max",old.get("retail_max"));}
  Map<String,Object>categories=db.config(shop,"category");if(!str(d,"category").isEmpty()&&categories.get("items") instanceof List)require(((List<?>)categories.get("items")).contains(str(d,"category")),"请选择已配置的商品分类");if(!str(d,"freight_id").isEmpty())db.one("select id from hx_document where id=? and shop_id=? and kind='freight' and status='ACTIVE'",str(d,"freight_id"),shop);
  require(d.get("gallery") instanceof List,"商品图片格式错误");List<?>gallery=(List<?>)d.get("gallery");require(gallery.size()<=9,"最多9张商品图片");Set<Object>seen=new HashSet<>();for(Object file:gallery){require(file instanceof String&&seen.add(file),"商品图片重复或格式错误");Map<String,Object>f=db.one("select member_id from hx_attachment where id=? and shop_id=? and purpose='PRODUCT'",file,shop);require(actor.platform||number(f,"member_id")==actor.id||((List<?>)old.get("gallery")).contains(file),"不能关联其他人员的未发布商品图片");}
  String asset=str(input,"asset");if(asset.startsWith("FILE"))require(gallery.contains(asset),"商品封面须来自本商品图库");
  if(db.optional("select sku_id from hx_sku_detail where shop_id=? and sku_id=?",shop,sku)==null)db.jdbc().update("insert into hx_sku_detail(shop_id,sku_id) values(?,?)",shop,sku);
  db.jdbc().update("update hx_sku_detail set category=?,brand=?,product_group=?,description=?,freight_id=?,weight_grams=?,allow_loose=?,gallery_json=?,retail_min=?,retail_max=? where shop_id=? and sku_id=?",str(d,"category"),brand,str(d,"product_group"),str(d,"description"),str(d,"freight_id"),number(d,"weight_grams"),bool(d,"allow_loose"),db.json(map("items",gallery)),number(d,"retail_min"),number(d,"retail_max"),shop,sku);
 }
 public static void categories(Map<String,Object>b){require(b.get("items") instanceof List,"请填写商品分类列表");List<?>list=(List<?>)b.get("items");require(list.size()<=100,"最多100个分类");Set<String>seen=new HashSet<>();for(Object raw:list)require(raw instanceof String&&!raw.toString().trim().isEmpty()&&raw.toString().length()<=80&&seen.add(raw.toString()),"分类名称不能为空或重复");}
 public void retailQuantity(long shop,Map<String,Object>sku,long qty){Map<String,Object>d=detail(shop,str(sku,"id"));if(!bool(d,"allow_loose"))require(qty%number(sku,"box_size")==0&&qty>=number(sku,"box_size")*number(sku,"min_boxes"),"该SKU不允许散卖，须满足整箱及最低箱数");}
 public void permittedRetail(long shop,String sku,long price){if(shop==900)return;Map<String,Object>standard=detail(900,sku);require(price>=number(standard,"retail_min")&&(number(standard,"retail_max")==0||price<=number(standard,"retail_max")),"零售价超出总部允许范围");}
 public Map<String,Object> settings(Actor a,long shop){db.manage(a,shop,"CATALOG");Map<String,Object>result=new LinkedHashMap<>(db.parse(db.one("select settings from hx_shop where id=?",shop).get("settings")));result.put("categories",db.config(shop,"category").getOrDefault("items",Collections.emptyList()));return result;}
}
