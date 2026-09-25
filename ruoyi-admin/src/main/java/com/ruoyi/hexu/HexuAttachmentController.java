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
  String header=req.getHeader("Authorization");Actor a=header!=null&&header.startsWith("Bearer hx_")?auth.member(req):auth.admin();if(a.staff||Arrays.asList("FINANCE","LINK_CONFIRMATION","PRODUCT").contains(purpose)){require(Arrays.asList("FINANCE","LINK_CONFIRMATION","PRODUCT").contains(purpose),"平台上传入口仅支持财务、商品或活动确认图片");db.manage(a,shopId,"PRODUCT".equals(purpose)?"CATALOG":"LINK_CONFIRMATION".equals(purpose)?"OWNER":"FINANCE");}db.one("select id from hx_shop where id=?",shopId);require(Arrays.asList("IDENTITY","RETURN","REVIEW","PRODUCT","FINANCE","LINK_CONFIRMATION").contains(purpose),"附件用途无效");require(!file.isEmpty()&&file.getSize()<=5*1024*1024,"图片大小须在5MB以内");byte[] bytes=file.getBytes();String mime=bytes.length>8&&(bytes[0]&255)==137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71?"image/png":bytes.length>3&&(bytes[0]&255)==255&&(bytes[1]&255)==216?"image/jpeg":"";require(!mime.isEmpty(),"仅支持真实PNG或JPEG图片");
  try(javax.imageio.stream.ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(input);require(readers.hasNext(),"无法读取图片");javax.imageio.ImageReader reader=readers.next();try{reader.setInput(input);require((long)reader.getWidth(0)*reader.getHeight(0)<=20000000,"图片尺寸不能超过2000万像素");}finally{reader.dispose();}}
  byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);String id=id("FILE");db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values(?,?,?,?,?,?,?)",id,shopId,a.id,purpose,mime,iv,crypt(bytes,iv,Cipher.ENCRYPT_MODE));db.audit(a,shopId,"ATTACHMENT_UPLOAD",id,null,map("purpose",purpose,"bytes",bytes.length));return HexuAppController.ok(map("id",id,"mime",mime,"size",bytes.length));
 }
 @GetMapping("/product/{id}")public ResponseEntity<byte[]> publishedProduct(@PathVariable String id)throws Exception{
  Map<String,Object>d=db.one("select * from hx_attachment where id=? and purpose='PRODUCT'",id);boolean published=false;for(Map<String,Object>p:db.jdbc().queryForList("select k.id,k.asset from hx_sku k join hx_shop s on s.id=k.shop_id where k.shop_id=? and k.status='ACTIVE' and s.status='ACTIVE'",number(d,"shop_id"))){if(id.equals(str(p,"asset"))||((List<?>)new HexuProductData(db).detail(number(d,"shop_id"),str(p,"id")).get("gallery")).contains(id)){published=true;break;}}require(published,"商品图片尚未发布");return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(d,"mime"))).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(crypt((byte[])d.get("cipher_bytes"),(byte[])d.get("iv"),Cipher.DECRYPT_MODE));
 }
 @GetMapping("/{id}")public ResponseEntity<byte[]> read(HttpServletRequest req,@PathVariable String id)throws Exception{
  String header=req.getHeader("Authorization");Actor a=header!=null&&header.startsWith("Bearer hx_")?auth.member(req):auth.admin();Map<String,Object>d=db.one("select * from hx_attachment where id=?",id);if(a.staff||number(d,"member_id")!=a.id)db.manage(a,number(d,"shop_id"),Arrays.asList("IDENTITY","LINK_CONFIRMATION").contains(str(d,"purpose"))?"OWNER":"FINANCE".equals(str(d,"purpose"))?"FINANCE":"PRODUCT".equals(str(d,"purpose"))?"CATALOG":"SUPPORT");db.audit(a,number(d,"shop_id"),"ATTACHMENT_READ",id,null,null);byte[] bytes=crypt((byte[])d.get("cipher_bytes"),(byte[])d.get("iv"),Cipher.DECRYPT_MODE);return ResponseEntity.ok().contentType(MediaType.parseMediaType(str(d,"mime"))).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(bytes);
 }
}
