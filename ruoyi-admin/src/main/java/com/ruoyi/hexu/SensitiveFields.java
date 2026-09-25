package com.ruoyi.hexu;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ruoyi.hexu.BusinessException.require;
/** Encrypt personal financial identifiers before persistence; browser responses keep only masks. */
public final class SensitiveFields {
 private SensitiveFields(){}
 public static void seal(Map<String,Object> body,String secret){
  for(String field:Arrays.asList("bank","accountNumber","idCard","identityNumber","银行卡号","身份证号")){
   Object raw=body.get(field);if(raw==null)continue;String value=raw.toString();if(value.isEmpty()||value.contains("*"))continue;
   require(secret!=null&&secret.length()>=32,"请在服务端配置敏感信息加密密钥后提交证件或结算资料");
   try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)),"AES"),new GCMParameterSpec(128,iv));String encrypted=Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));body.put("_secure_"+field,encrypted);body.put(field,"****"+value.substring(Math.max(0,value.length()-4)));}catch(Exception e){throw new BusinessException("敏感资料加密失败");}
  }
 }
 public static void redact(Map<String,Object> body){body.keySet().removeIf(k->k.startsWith("_secure_"));}
}
