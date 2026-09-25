package com.ruoyi.hexu;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Anonymous @RestController @RequestMapping("/hexu/app/wechat")
public class HexuWechatController {
 private final WechatIdentityGateway gateway;private final PaymentGateway payments;private final HexuIdentity auth;private final HexuStore db;private final HexuCatalogService catalog;private final HexuOrderService orders;
 public HexuWechatController(WechatIdentityGateway gateway,PaymentGateway payments,HexuIdentity auth,HexuStore db,HexuCatalogService catalog,HexuOrderService orders){this.gateway=gateway;this.payments=payments;this.auth=auth;this.db=db;this.catalog=catalog;this.orders=orders;}
 @Transactional @PostMapping("/login") public Object login(@RequestBody Map<String,Object>b){
  require(bool(b,"consent")&&!str(b,"code").isEmpty(),"请先确认协议并获取微信登录凭证");
  Map<String,Object> verified=gateway.exchangeCode(str(b,"code"));String appId=str(verified,"appId"),openId=str(verified,"openId");require(!appId.isEmpty()&&!openId.isEmpty(),"登录渠道返回无效身份");
  Map<String,Object> identity=db.optional("select * from hx_wechat_account where app_id=? and open_id=? for update",appId,openId);long member;
  if(identity==null){member=ThreadLocalRandom.current().nextLong(10000,9000000000000L);db.jdbc().update("insert into hx_member(id,name,authorized) values(?,'微信用户',true)",member);db.jdbc().update("insert into hx_wechat_account(app_id,open_id,member_id) values(?,?,?)",appId,openId,member);}else member=number(identity,"member_id");
  Actor a=new Actor(member,false,false);db.member(a);long shop=number(b,"shopId");catalog.bind(a,shop,str(b,"invite"));db.audit(a,shop,"LOGIN_CONSENT",String.valueOf(member),null,map("consent",true));return HexuAppController.ok(map("token",auth.session(member),"memberId",member));
 }
 @Transactional @PostMapping("/phone") public Object phone(HttpServletRequest req,@RequestBody Map<String,Object>b){Actor a=auth.member(req);require(bool(b,"consent"),"手机号用途需单独授权");Map<String,Object> verified=gateway.authorizedPhone(str(b,"code"));String phone=str(verified,"phone");require(phone.matches("1[3-9][0-9]{9}"),"手机号授权结果无效");db.jdbc().update("update hx_member set phone=?,authorized=true where id=?",phone,a.id);db.audit(a,0,"PHONE_CONSENT",String.valueOf(a.id),null,map("consent",true));return HexuAppController.ok(map("phone",phone));}
 @PostMapping("/payment/{orderId}") public Object payment(HttpServletRequest req,@PathVariable String orderId){Actor a=auth.member(req);Map<String,Object> identity=db.one("select * from hx_wechat_account where member_id=?",a.id);Map<String,Object> p=orders.payment(a,orderId);return HexuAppController.ok(payments.createPayment(p,str(identity,"open_id")));}
}
