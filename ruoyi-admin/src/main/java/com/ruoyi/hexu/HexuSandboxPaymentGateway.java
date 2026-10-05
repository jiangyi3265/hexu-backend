package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Local channel simulator. Never advertises a real merchant as ready or verifies real callbacks. */
@Component
@Primary
@Profile("hexu-dev")
@ConditionalOnProperty(name = "hexu.sandbox.enabled", havingValue = "true")
public class HexuSandboxPaymentGateway implements PaymentGateway {
  private final HexuStore db;
  private final HexuFinanceService finance;

  public HexuSandboxPaymentGateway(HexuStore db, @Lazy HexuFinanceService finance) {
    this.db = db;
    this.finance = finance;
  }

  private void localOnly() {
    require(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes,
        "本地模拟渠道仅限本机请求");
    HttpServletRequest request =
        ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
    require(Arrays.asList("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
        .contains(request.getRemoteAddr()), "本地模拟渠道仅限本机请求");
  }

  @Override
  public Map<String, Object> createPayment(Map<String, Object> payment, String openId) {
    localOnly();
    require("CREATED".equals(str(payment, "status")), "支付单当前不可支付");
    return map("sandbox", true, "status", "AWAITING_SANDBOX_CONFIRMATION",
        "paymentId", payment.get("id"), "amount", payment.get("amount"),
        "confirmPath", "/hexu/dev/payment/" + payment.get("id"),
        "message", "仅本机模拟，尚未付款；须单独确认本地沙盒支付");
  }

  @Override
  @Transactional
  public Map<String, Object> submitRefund(Map<String, Object> refund, String merchantNo) {
    localOnly();
    refund = db.one("select * from hx_refund where id=? for update", refund.get("id"));
    Map<String, Object> order = db.one("select merchant_no from hx_order where id=?", refund.get("order_id"));
    require(merchantNo.equals(str(order, "merchant_no")), "退款商户不匹配");
    if ("SUCCESS".equals(str(refund, "status"))) {
      require(db.optional("select id from hx_audit where action='SANDBOX_REFUND' and reference_id=?", refund.get("id")) != null,
          "该退款不是本地沙盒渠道结果");
      return map("sandbox", true, "status", "SUCCESS", "channelRef",
          "SANDBOX-" + refund.get("id"), "amount", refund.get("amount"), "refund", refund);
    }
    require("APPROVED".equals(str(refund, "status")), "退款未审核或尚未验收");
    Map<String, Object> result = finance.refundConfirmed(str(refund, "id"));
    db.audit(new Actor(0, true, true), number(refund, "shop_id"), "SANDBOX_REFUND",
        str(refund, "id"), map("status", "APPROVED"),
        map("status", result.get("status"), "amount", refund.get("amount"), "sandbox", true));
    return map("sandbox", true, "status", "SUCCESS", "channelRef",
        "SANDBOX-" + refund.get("id"), "amount", refund.get("amount"),
        "refund", result);
  }

  @Override
  @Transactional
  public Map<String, Object> submitPayout(Map<String, Object> withdrawal, String merchantNo) {
    localOnly();
    withdrawal = db.one("select * from hx_withdrawal where id=? for update", withdrawal.get("id"));
    require(merchantNo.equals(str(withdrawal, "merchant_no")), "提现商户不匹配");
    if ("PAID".equals(str(withdrawal, "status"))) {
      require(str(withdrawal, "channel_ref").equals("SANDBOX-" + withdrawal.get("id"))
          && db.optional("select id from hx_audit where action='SANDBOX_PAYOUT' and reference_id=?", withdrawal.get("id")) != null,
          "该提现不是本地沙盒渠道结果");
      return map("sandbox", true, "status", "PAID", "channelRef",
          withdrawal.get("channel_ref"), "amount", withdrawal.get("net"), "withdrawal", withdrawal);
    }
    require("APPROVED".equals(str(withdrawal, "status")), "提现尚未审核");
    String reference = "SANDBOX-" + withdrawal.get("id");
    Map<String, Object> result = finance.payoutResult(str(withdrawal, "id"), true, reference, "本地沙盒模拟到账");
    db.audit(new Actor(0, true, true), number(withdrawal, "shop_id"), "SANDBOX_PAYOUT",
        str(withdrawal, "id"), map("status", "APPROVED"),
        map("status", result.get("status"), "net", withdrawal.get("net"), "sandbox", true));
    return map("sandbox", true, "status", "PAID", "channelRef", reference,
        "amount", withdrawal.get("net"), "withdrawal", result);
  }

  @Override
  public Map<String, Object> query(String operation, String id, String merchantNo) {
    localOnly();
    Map<String, Object> row;
    String status;
    long amount;
    if ("payment".equals(operation)) {
      row = db.one("select * from hx_payment where id=?", id);
      status = "SUCCESS".equals(str(row, "status"))
          && str(row, "channel_ref").startsWith("SANDBOX-") ? "SUCCESS" : "PENDING";
      amount = number(row, "amount");
    } else if ("refund".equals(operation)) {
      row = db.one("select r.*,o.merchant_no from hx_refund r join hx_order o on o.id=r.order_id where r.id=?", id);
      status = "SUCCESS".equals(str(row, "status"))
          && db.optional("select id from hx_audit where action='SANDBOX_REFUND' and reference_id=?", id) != null
          ? "SUCCESS" : "PENDING";
      amount = number(row, "amount");
    } else if ("payout".equals(operation)) {
      row = db.one("select * from hx_withdrawal where id=?", id);
      status = "PAID".equals(str(row, "status"))
          && str(row, "channel_ref").startsWith("SANDBOX-") ? "SUCCESS" : "PENDING";
      amount = number(row, "net");
    } else throw new BusinessException("渠道查询类型无效");
    require(merchantNo.equals(str(row, "merchant_no")), "渠道商户不匹配");
    return map("sandbox", true, "verified", "SUCCESS".equals(status), "status", status,
        "channelRef", "SUCCESS".equals(status) ? "SANDBOX-" + id : "",
        "merchantNo", merchantNo, "amount", amount);
  }
}
