package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;

/** A buyer may confirm only their own demonstration payment, with no external charge. */
@Anonymous
@RestController
@Profile("hexu-prod")
@ConditionalOnExpression("${hexu.demo.enabled:false} && !${hexu.wechat-pay.enabled:false}")
@RequestMapping("/hexu/app/demo")
public class HexuDemoController {
  private final HexuIdentity auth;
  private final HexuStore db;
  private final HexuOrderService orders;

  public HexuDemoController(HexuIdentity auth, HexuStore db, HexuOrderService orders) {
    this.auth = auth;
    this.db = db;
    this.orders = orders;
  }

  @Transactional
  @PostMapping("/payments/{id}/confirm")
  public Object confirmPayment(HttpServletRequest request, @PathVariable String id) {
    Actor buyer = auth.member(request);
    Map<String, Object> payment = db.one(
        "select p.*,o.buyer_id,o.shop_id from hx_payment p join hx_order o on o.id=p.order_id where p.id=?", id);
    require(number(payment, "buyer_id") == buyer.id, "无权操作此支付单");
    Map<String, Object> result = orders.paymentConfirmed(id, str(payment, "merchant_no"),
        number(payment, "amount"), "DEMO-" + id);
    if (db.optional("select id from hx_audit where action='DEMO_PAYMENT' and reference_id=?", id) == null)
      db.audit(buyer, number(payment, "shop_id"), "DEMO_PAYMENT", id, null,
          map("amount", payment.get("amount"), "sandbox", true));
    Map<String, Object> response = new LinkedHashMap<>(result);
    response.put("sandbox", true);
    return HexuAppController.ok(response);
  }
}
