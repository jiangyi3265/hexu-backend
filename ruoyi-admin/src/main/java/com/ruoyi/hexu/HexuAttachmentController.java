package com.ruoyi.hexu;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import javax.servlet.http.HttpServletRequest;
import javax.imageio.ImageIO;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.io.ByteArrayInputStream;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Anonymous @RestController @RequestMapping("/hexu/app/attachments")
public class HexuAttachmentController {
 private final HexuIdentity auth;private final HexuStore db;@Value("${hexu.sensitive-key:}")private String secret;
 public HexuAttachmentController(HexuIdentity auth,HexuStore db){this.auth=auth;this.db=db;}
 private byte[] crypt(byte[] bytes,byte[] iv,int mode)throws Exception{require(secret!=null&&secret.length()>=32,"资料加密密钥尚未配置");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)),"AES"),new GCMParameterSpec(128,iv));return c.doFinal(bytes);}
 @PostMapping public Object upload(HttpServletRequest req,@RequestParam long shopId,@RequestParam(defaultValue="IDENTITY")String purpose,@RequestParam MultipartFile file)throws Exception{
  String header=req.getHeader("Authorization");Actor a=header!=null&&header.startsWith("Bearer hx_")?auth.member(req):auth.admin();
  if(a.staff||Arrays.asList("FINANCE","LINK_CONFIRMATION","PRODUCT","MERCHANT","STOREFRONT").contains(purpose)){
   require(Arrays.asList("FINANCE","LINK_CONFIRMATION","PRODUCT","MERCHANT","STOREFRONT","RETURN","SUPPORT").contains(purpose),"平台上传入口仅支持财务、商品、商户、装修、售后验收、客服或活动确认图片");
   if("RETURN".equals(purpose))db.manage(a,shopId,"WAREHOUSE","SUPPORT");
   else if("SUPPORT".equals(purpose))db.manage(a,shopId,"SUPPORT");
   else if("STOREFRONT".equals(purpose))db.manage(a,shopId,"CATALOG","OPERATOR");
   else db.manage(a,shopId,Arrays.asList("PRODUCT").contains(purpose)?"CATALOG":Arrays.asList("LINK_CONFIRMATION","MERCHANT").contains(purpose)?"OWNER":"FINANCE");
  }
  if(!"AVATAR".equals(purpose)||shopId!=0)db.one("select id from hx_shop where id=?",shopId);require(Arrays.asList("IDENTITY","RETURN","REVIEW","PRODUCT","FINANCE","LINK_CONFIRMATION","MERCHANT","STOREFRONT","SUPPORT","AVATAR").contains(purpose),"附件用途无效");require(!file.isEmpty()&&file.getSize()<=5*1024*1024,"图片大小须在5MB以内");byte[] bytes=file.getBytes();String mime=bytes.length>8&&(bytes[0]&255)==137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71?"image/png":bytes.length>3&&(bytes[0]&255)==255&&(bytes[1]&255)==216?"image/jpeg":"";require(!mime.isEmpty(),"仅支持真实PNG或JPEG图片");
  try(javax.imageio.stream.ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
   require(input!=null,"无法读取图片");
   Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(input);
   require(readers.hasNext(),"无法读取图片");
   javax.imageio.ImageReader reader=readers.next();
   try{
    reader.setInput(input);
    int width,height;
    try{width=reader.getWidth(0);height=reader.getHeight(0);}
    catch(java.io.IOException|RuntimeException e){throw new BusinessException("无法读取图片");}
    require((long)width*height<=20000000,"图片尺寸不能超过2000万像素");
    java.awt.image.BufferedImage decoded;
    try{decoded=reader.read(0);}
    catch(java.io.IOException|RuntimeException e){throw new BusinessException("无法读取完整图片");}
    require(decoded!=null,"无法读取完整图片");
   }finally{reader.dispose();}
  }
  byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);String id=id("FILE");long scope="AVATAR".equals(purpose)?0:shopId;db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values(?,?,?,?,?,?,?)",id,scope,a.id,purpose,mime,iv,crypt(bytes,iv,Cipher.ENCRYPT_MODE));db.audit(a,scope,"ATTACHMENT_UPLOAD",id,null,map("purpose",purpose,"bytes",bytes.length));return HexuAppController.ok(map("id",id,"mime",mime,"size",bytes.length));
 }
 @GetMapping("/avatar/{id}")public ResponseEntity<byte[]> publishedAvatar(@PathVariable String id)throws Exception{
  Map<String,Object>d=db.one("select t.* from hx_attachment t join hx_member m on m.id=t.member_id where t.id=? and t.purpose='AVATAR' and m.status='ACTIVE'",id);
  Map<String,Object> profile=db.optional("select body_json from hx_document where member_id=? and kind='profile' and status='ACTIVE' order by case when shop_id=0 then 0 else 1 end,updated_at desc,id desc limit 1",number(d,"member_id"));
  require(profile!=null&&id.equals(str(db.parse(profile.get("body_json")),"avatarId")),"头像尚未保存到个人资料");return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(d,"mime"))).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(crypt((byte[])d.get("cipher_bytes"),(byte[])d.get("iv"),Cipher.DECRYPT_MODE));
 }
 @GetMapping("/review/{id}")public ResponseEntity<byte[]> publishedReview(@PathVariable String id)throws Exception{
  Map<String,Object>d=db.one("select * from hx_attachment where id=? and purpose='REVIEW'",id);db.shop(number(d,"shop_id"));
  require(new HexuReviews(db).publishedImage(d),"评价图片尚未审核公开");
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(d,"mime"))).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(crypt((byte[])d.get("cipher_bytes"),(byte[])d.get("iv"),Cipher.DECRYPT_MODE));
 }
 @GetMapping("/product/{id}")public ResponseEntity<byte[]> publishedProduct(@PathVariable String id)throws Exception{
  Map<String,Object>d=db.one("select * from hx_attachment where id=? and purpose='PRODUCT'",id);boolean published=false;for(Map<String,Object>p:db.jdbc().queryForList("select k.id,k.asset from hx_sku k join hx_shop s on s.id=k.shop_id where k.shop_id=? and k.status='ACTIVE' and s.status='ACTIVE'",number(d,"shop_id"))){if(id.equals(str(p,"asset"))||((List<?>)new HexuProductData(db).detail(number(d,"shop_id"),str(p,"id")).get("gallery")).contains(id)){published=true;break;}}require(published,"商品图片尚未发布");return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(d,"mime"))).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(crypt((byte[])d.get("cipher_bytes"),(byte[])d.get("iv"),Cipher.DECRYPT_MODE));
 }
 @GetMapping("/order-cover/{orderId}/{lineId}")public ResponseEntity<byte[]> orderCover(HttpServletRequest req,@PathVariable String orderId,@PathVariable long lineId)throws Exception{
  String header=req.getHeader("Authorization");Actor a=header!=null&&header.startsWith("Bearer hx_")?auth.member(req):auth.admin();
  Map<String,Object>order=db.one("select id,shop_id,buyer_id from hx_order where id=?",orderId);long shop=number(order,"shop_id");
  if(a.staff||number(order,"buyer_id")!=a.id)db.manage(a,shop,"ORDER","WAREHOUSE","FINANCE","SUPPORT");
  Map<String,Object>line=db.one("select snapshot_json from hx_order_line where id=? and order_id=?",lineId,orderId);
  Object saved=db.parse(line.get("snapshot_json")).get("asset");
  String prefix="/hexu/app/attachments/product/";
  require(saved instanceof String&&((String)saved).matches("/hexu/app/attachments/product/FILE[0-9a-f]{32}"),"订单没有可读取的历史商品封面");
  String id=((String)saved).substring(prefix.length());
  Map<String,Object>d=db.one("select mime,iv,cipher_bytes from hx_attachment where id=? and shop_id=? and purpose='PRODUCT'",id,shop);
  require(Arrays.asList("image/png","image/jpeg").contains(str(d,"mime")),"历史商品封面格式不支持");
  db.audit(a,shop,"ORDER_COVER_READ",orderId+":"+lineId,null,null);
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(d,"mime"))).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(crypt((byte[])d.get("cipher_bytes"),(byte[])d.get("iv"),Cipher.DECRYPT_MODE));
 }
 @GetMapping("/storefront/{id}")public ResponseEntity<byte[]> publishedStorefront(@PathVariable String id)throws Exception{
  Map<String,Object>d=db.one("select * from hx_attachment where id=? and purpose='STOREFRONT'",id);
  db.shop(number(d,"shop_id"));
  int used=db.jdbc().queryForObject("select count(*) from hx_document where shop_id=? and status='ACTIVE' and kind in ('decoration','page_schema') and body_json like ?",Integer.class,number(d,"shop_id"),"%"+id+"%");
  require(used>0,"装修图片尚未发布");
  return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(d,"mime"))).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(crypt((byte[])d.get("cipher_bytes"),(byte[])d.get("iv"),Cipher.DECRYPT_MODE));
 }
 @GetMapping("/{id}")public ResponseEntity<byte[]> read(HttpServletRequest req,@PathVariable String id)throws Exception{
  String header=req.getHeader("Authorization");Actor a=header!=null&&header.startsWith("Bearer hx_")?auth.member(req):auth.admin();Map<String,Object>d=db.one("select * from hx_attachment where id=?",id);if(a.staff||number(d,"member_id")!=a.id){
   String purpose=str(d,"purpose");long shop=number(d,"shop_id");
   if("RETURN".equals(purpose))db.manage(a,shop,"WAREHOUSE","SUPPORT");
   else if("STOREFRONT".equals(purpose))db.manage(a,shop,"CATALOG","OPERATOR");
   else if("SUPPORT".equals(purpose)||("IDENTITY".equals(purpose)&&legacySupportAttachment(d)))db.manage(a,shop,"SUPPORT");
   else db.manage(a,shop,Arrays.asList("IDENTITY","LINK_CONFIRMATION","MERCHANT").contains(purpose)?"OWNER":"FINANCE".equals(purpose)?"FINANCE":"PRODUCT".equals(purpose)?"CATALOG":"SUPPORT");
  }db.audit(a,number(d,"shop_id"),"ATTACHMENT_READ",id,null,null);byte[] bytes=crypt((byte[])d.get("cipher_bytes"),(byte[])d.get("iv"),Cipher.DECRYPT_MODE);return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(d,"mime"))).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(bytes);
 }
 private boolean legacySupportAttachment(Map<String,Object> attachment){
  String id=str(attachment,"id");
  for(Map<String,Object> document:db.jdbc().queryForList("select body_json from hx_document where shop_id=? and member_id=? and kind='support' and status<>'DELETED'",number(attachment,"shop_id"),number(attachment,"member_id"))){
   Object uploads=db.parse(document.get("body_json")).get("uploads");
   if(uploads instanceof List&&((List<?>)uploads).contains(id))return true;
  }
  return false;
 }
}
