package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit online demonstration channel. Its references never represent external transfers. */
@Component
@Primary
@Profile("hexu-prod")
@ConditionalOnExpression("${hexu.demo.enabled:false} && !${hexu.wechat-pay.enabled:false}")
public class HexuDemoPaymentGateway implements PaymentGateway {
  private final HexuStore db;
  private final HexuFinanceService finance;

  public HexuDemoPaymentGateway(HexuStore db, @Lazy HexuFinanceService finance) {
    this.db = db;
    this.finance = finance;
  }

  @Override
  public Map<String, Object> createPayment(Map<String, Object> payment, String openId) {
    require("CREATED".equals(str(payment, "status")), "支付单当前不可支付");
    return map("sandbox", true, "paymentId", payment.get("id"), "amount", payment.get("amount"),
        "confirmPath", "/hexu/app/demo/payments/" + payment.get("id") + "/confirm",
        "message", "演示支付，不发生真实扣款");
  }

  @Override
  @Transactional
  public Map<String, Object> submitRefund(Map<String, Object> refund, String merchantNo) {
    Map<String, Object> order = db.one(
        "select p.status,p.channel_ref,o.merchant_no from hx_order o join hx_payment p on p.order_id=o.id where o.id=?",
        refund.get("order_id"));
    require(merchantNo.equals(str(order, "merchant_no")), "退款商户不匹配");
    require("SUCCESS".equals(str(order, "status")) && str(order, "channel_ref").startsWith("DEMO-"),
        "只有演示支付订单可模拟退款");
    refund = db.one("select * from hx_refund where id=? for update", refund.get("id"));
    if (!"SUCCESS".equals(str(refund, "status"))) {
      require("APPROVED".equals(str(refund, "status")), "退款未审核或尚未验收");
      Map<String, Object> before = refund;
      refund = finance.refundConfirmed(str(refund, "id"));
      db.audit(new Actor(0, true, true), number(refund, "shop_id"), "DEMO_REFUND",
          str(refund, "id"), before, map("status", refund.get("status"), "sandbox", true));
    }
    return map("sandbox", true, "status", "SUCCESS", "channelRef", "DEMO-" + refund.get("id"),
        "amount", refund.get("amount"), "refund", refund);
  }

  @Override
  @Transactional
  public Map<String, Object> submitPayout(Map<String, Object> withdrawal, String merchantNo) {
    withdrawal = db.one("select * from hx_withdrawal where id=? for update", withdrawal.get("id"));
    require(merchantNo.equals(str(withdrawal, "merchant_no")), "提现商户不匹配");
    String id = str(withdrawal, "id"), reference = "DEMO-" + id;
    if (!"PAID".equals(str(withdrawal, "status"))) {
      require("APPROVED".equals(str(withdrawal, "status")), "提现尚未审核通过");
      Map<String, Object> before = withdrawal;
      withdrawal = finance.payoutResult(id, true, reference, "演示到账，未发生真实转账");
      db.audit(new Actor(0, true, true), number(withdrawal, "shop_id"), "DEMO_PAYOUT",
          id, before, map("status", withdrawal.get("status"), "sandbox", true));
    } else require(reference.equals(str(withdrawal, "channel_ref")), "该提现不是演示渠道结果");
    return map("sandbox", true, "status", "PAID", "channelRef", reference,
        "amount", withdrawal.get("net"), "withdrawal", withdrawal);
  }

  @Override
  public Map<String, Object> query(String operation, String id, String merchantNo) {
    Map<String, Object> row;
    long amount;
    String state;
    if ("payment".equals(operation)) {
      row = db.one("select * from hx_payment where id=?", id);
      amount = number(row, "amount");
      state = "SUCCESS".equals(str(row, "status")) ? "SUCCESS" : "PENDING";
    } else if ("refund".equals(operation)) {
      row = db.one("select r.*,o.merchant_no from hx_refund r join hx_order o on o.id=r.order_id where r.id=?", id);
      amount = number(row, "amount");
      state = "SUCCESS".equals(str(row, "status")) ? "SUCCESS" : "PENDING";
    } else if ("payout".equals(operation)) {
      row = db.one("select * from hx_withdrawal where id=?", id);
      amount = number(row, "net");
      state = "PAID".equals(str(row, "status")) ? "SUCCESS" : "PENDING";
    } else throw new BusinessException("渠道查询类型无效");
    require(merchantNo.equals(str(row, "merchant_no")), "渠道商户不匹配");
    if ("SUCCESS".equals(state))
      require(("refund".equals(operation)
          ? db.optional("select id from hx_audit where action='DEMO_REFUND' and reference_id=?", id) != null
          : ("DEMO-" + id).equals(str(row, "channel_ref"))), "该记录不是演示渠道结果");
    return map("sandbox", true, "verified", "SUCCESS".equals(state), "status", state,
        "channelRef", "SUCCESS".equals(state) ? "DEMO-" + id : "",
        "merchantNo", merchantNo, "amount", amount);
  }
}
