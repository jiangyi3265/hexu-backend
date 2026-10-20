package com.ruoyi.hexu;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@RestController @RequestMapping("/hexu/admin/channel")
public class HexuChannelController {
 private final HexuIdentity auth;private final HexuStore db;private final PaymentGateway gateway;private final HexuChannelInbox inbox;
 @Value("${hexu.wechat-pay.enabled:false}") private boolean realPaymentEnabled;
 @Value("${hexu.wechat-pay.all-shops-direct:false}") private boolean allShopsDirect;
 @Value("${hexu.wechat-pay.direct.merchant-id:}") private String companyMerchantNo;
 public HexuChannelController(HexuIdentity auth,HexuStore db,PaymentGateway gateway,HexuChannelInbox inbox){this.inbox=inbox;this.auth=auth;this.db=db;this.gateway=gateway;}
 String statementMerchant(Actor actor, long shopId) {
  db.manage(actor, shopId, "FINANCE");
  Map<String, Object> shop = db.one("select merchant_no from hx_shop where id=?", shopId);
  if (realPaymentEnabled && allShopsDirect) {
   require(actor.platform, "公司统一收款账单仅平台财务可查看");
   return companyMerchantNo;
  }
  return str(shop, "merchant_no");
 }
 @GetMapping("/statement") public Object statement(@RequestParam long shopId, @RequestParam String date) {
  String merchantNo = statementMerchant(auth.admin(), shopId);
  try { java.time.LocalDate.parse(date); }
  catch (Exception e) { throw new BusinessException("请填写有效账单日期"); }
  return HexuAppController.ok(gateway.queryStatement(merchantNo, date));
 }
 @PostMapping("/retry/{id}") public Object retry(@PathVariable String id){inbox.authorize(auth.admin(),id);try{return HexuAppController.ok(inbox.apply(id));}catch(RuntimeException e){inbox.failed(id,e.getMessage());throw e;}}
 @GetMapping("/query/{operation}/{id}") public Object query(@PathVariable String operation,@PathVariable String id){require(java.util.Arrays.asList("payment","refund","payout").contains(operation),"渠道查询类型无效");Map<String,Object>b="payment".equals(operation)?db.one("select p.*,o.shop_id from hx_payment p join hx_order o on o.id=p.order_id where p.id=?",id):"refund".equals(operation)?db.one("select r.*,o.merchant_no from hx_refund r join hx_order o on o.id=r.order_id where r.id=?",id):db.one("select w.*,coalesce(w.merchant_no,s.merchant_no) as merchant_no from hx_withdrawal w join hx_shop s on s.id=w.shop_id where w.id=?",id);db.manage(auth.admin(),number(b,"shop_id"),"FINANCE");return HexuAppController.ok(gateway.query(operation,id,str(b,"merchant_no")));}
 @PostMapping("/recover/{operation}/{id}") public Object recover(@PathVariable String operation,@PathVariable String id){require(java.util.Arrays.asList("payment","refund").contains(operation),"仅支持支付或退款渠道查单补单");Map<String,Object>b="payment".equals(operation)?db.one("select p.*,o.shop_id from hx_payment p join hx_order o on o.id=p.order_id where p.id=?",id):db.one("select r.*,o.merchant_no from hx_refund r join hx_order o on o.id=r.order_id where r.id=?",id);db.manage(auth.admin(),number(b,"shop_id"),"FINANCE");Map<String,Object>found=gateway.query(operation,id,str(b,"merchant_no"));require(bool(found,"verified")&&"SUCCESS".equals(str(found,"status"))&&!str(found,"channelRef").isEmpty()&&number(found,"amount")==number(b,"amount")&&str(found,"merchantNo").equals(str(b,"merchant_no")),"渠道尚未确认成功或金额、商户不匹配");String key=inbox.accept(map("type","payment".equals(operation)?"PAYMENT_SUCCESS":"REFUND_SUCCESS","id",id,"merchantNo",str(b,"merchant_no"),"amount",number(b,"amount"),"channelRef",str(found,"channelRef")));return HexuAppController.ok(inbox.apply(key));}
 @PostMapping("/refund/{id}") public Object refund(@PathVariable String id){Map<String,Object>r=db.one("select * from hx_refund where id=?",id);db.manage(auth.admin(),number(r,"shop_id"),"FINANCE");require("APPROVED".equals(str(r,"status")),"退款未审核或尚未验收");Map<String,Object>o=db.one("select * from hx_order where id=?",r.get("order_id"));return HexuAppController.ok(gateway.submitRefund(r,str(o,"merchant_no")));}
 @PostMapping("/payout/{id}") public Object payout(@PathVariable String id){Map<String,Object>w=db.one("select * from hx_withdrawal where id=?",id);db.manage(auth.admin(),number(w,"shop_id"),"FINANCE");require("APPROVED".equals(str(w,"status")),"提现尚未审核");Map<String,Object>s=db.one("select merchant_no from hx_shop where id=?",number(w,"shop_id"));return HexuAppController.ok(gateway.submitPayout(w,str(w,"merchant_no").isEmpty()?str(s,"merchant_no"):str(w,"merchant_no")));}
}
