package com.ruoyi.hexu;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Anonymous @RestController @RequestMapping("/hexu/app/wechat")
public class HexuWechatController {
 private final WechatIdentityGateway gateway;private final PaymentGateway payments;private final HexuIdentity auth;private final HexuStore db;private final HexuCatalogService catalog;private final HexuOrderService orders;private final HexuPolicies policies;private final HexuFinanceService finance;
 public HexuWechatController(WechatIdentityGateway gateway,PaymentGateway payments,HexuIdentity auth,HexuStore db,HexuCatalogService catalog,HexuOrderService orders,HexuPolicies policies){this(gateway,payments,auth,db,catalog,orders,policies,null);}
  @Autowired public HexuWechatController(WechatIdentityGateway gateway,PaymentGateway payments,HexuIdentity auth,HexuStore db,HexuCatalogService catalog,HexuOrderService orders,HexuPolicies policies,HexuFinanceService finance){this.gateway=gateway;this.payments=payments;this.auth=auth;this.db=db;this.catalog=catalog;this.orders=orders;this.policies=policies;this.finance=finance;}
 @Transactional @PostMapping("/login") public Object login(@RequestBody Map<String,Object>b){
  require(bool(b,"consent")&&!str(b,"code").isEmpty(),"请先确认协议并获取微信登录凭证");
  Map<String,Object> verified=gateway.exchangeCode(str(b,"code"));String appId=str(verified,"appId"),openId=str(verified,"openId");require(!appId.isEmpty()&&!openId.isEmpty(),"登录渠道返回无效身份");
  Map<String,Object> identity=db.optional("select * from hx_wechat_account where app_id=? and open_id=? for update",appId,openId);long member;
  if(identity==null){member=ThreadLocalRandom.current().nextLong(10000,9000000000000L);db.jdbc().update("insert into hx_member(id,name,authorized) values(?,'微信用户',true)",member);db.jdbc().update("insert into hx_wechat_account(app_id,open_id,member_id) values(?,?,?)",appId,openId,member);}else member=number(identity,"member_id");
  Actor a=new Actor(member,false,false);db.member(a);long shop=number(b,"shopId");policies.consent(a,shop,b,"WECHAT_LOGIN");catalog.bind(a,shop,str(b,"invite"));db.audit(a,shop,"LOGIN_CONSENT",String.valueOf(member),null,map("consent",true,"versions",b.get("versions")));return HexuAppController.ok(map("token",auth.session(member),"memberId",member));
 }
 @Transactional @PostMapping("/phone") public Object phone(HttpServletRequest req,@RequestBody Map<String,Object>b){Actor a=auth.member(req);require(bool(b,"consent"),"手机号用途需单独授权");Map<String,Object> profile=new HexuProfileService(db).authorizePhone(a,str(b,"code"),gateway);return HexuAppController.ok(map("phone",profile.get("phone")));}
 @PostMapping("/payment/{orderId}") public Object payment(HttpServletRequest req,@PathVariable String orderId){Actor a=auth.member(req);Map<String,Object> identity=db.one("select * from hx_wechat_account where member_id=?",a.id);Map<String,Object> p=orders.payment(a,orderId);return HexuAppController.ok(payments.createPayment(p,str(identity,"open_id")));}
 @PostMapping("/withdrawals/{id}/transfer") public Object transfer(HttpServletRequest req,@PathVariable String id){Actor a=auth.member(req);Map<String,Object>w=db.one("select * from hx_withdrawal where id=? for update",id);require(number(w,"member_id")==a.id,"无权操作此提现单");require("WECHAT".equals(str(w,"channel")),"当前提现渠道不是微信零钱");require(Arrays.asList("APPROVED","PROCESSING").contains(str(w,"status")),"提现尚未审核通过或已结束");Map<String,Object>s=db.one("select merchant_no from hx_shop where id=?",w.get("shop_id"));String merchant=str(w,"merchant_no").isEmpty()?str(s,"merchant_no"):str(w,"merchant_no");return HexuAppController.ok(payments.submitPayout(w,merchant));}
  @GetMapping("/withdrawals/{id}/transfer") public Object transferStatus(HttpServletRequest req,@PathVariable String id){require(finance!=null,"提现结算服务未就绪");Actor a=auth.member(req);Map<String,Object>w=db.one("select * from hx_withdrawal where id=?",id);require(number(w,"member_id")==a.id,"无权查询此提现单");require("WECHAT".equals(str(w,"channel")),"当前提现渠道不是微信零钱");Map<String,Object>s=db.one("select merchant_no from hx_shop where id=?",w.get("shop_id"));String merchant=str(w,"merchant_no").isEmpty()?str(s,"merchant_no"):str(w,"merchant_no");Map<String,Object>found=payments.query("payout",id,merchant);String state=str(found,"status");if(Arrays.asList("SUCCESS","FAIL","CANCELLED").contains(state)){Map<String,Object>settled=finance.payoutResult(id,"SUCCESS".equals(state),str(found,"channelRef"),"SUCCESS".equals(state)?"":"微信商家转账"+state);return HexuAppController.ok(map("provider",found,"withdrawal",settled));}db.jdbc().update("update hx_withdrawal set provider_state=?,provider_bill_no=COALESCE(NULLIF(provider_bill_no,''),?),provider_updated_at=CURRENT_TIMESTAMP where id=? and status in ('APPROVED','PROCESSING')",state,str(found,"channelRef"),id);return HexuAppController.ok(map("provider",found,"withdrawal",db.one("select * from hx_withdrawal where id=?",id)));}
}
