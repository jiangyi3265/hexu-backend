package com.ruoyi.hexu;

import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = HexuUnifiedDirectPaymentTest.Config.class)
@TestPropertySource(properties = {
    "hexu.wechat-pay.enabled=true",
    "hexu.wechat-pay.all-shops-direct=true",
    "hexu.wechat-pay.direct.merchant-id=1900000001"
})
class HexuUnifiedDirectPaymentTest {
  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean DataSource dataSource() {
      return new DriverManagerDataSource(
          "jdbc:h2:mem:unified-direct;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
          "sa", "");
    }

    @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
    @Bean ObjectMapper json() { return new ObjectMapper(); }
    @Bean DataSourceTransactionManager tx(DataSource source) {
      return new DataSourceTransactionManager(source);
    }
    @Bean HexuStore db(JdbcTemplate jdbc, ObjectMapper json) { return new HexuStore(jdbc, json); }
    @Bean HexuCatalogService catalog(HexuStore db) { return new HexuCatalogService(db); }
    @Bean HexuPolicies policies(HexuStore db) { return new HexuPolicies(db); }
    @Bean PaymentGateway paymentGateway() {
      return new UnconfiguredPaymentGateway() {
        @Override public boolean ready(String merchantNo) {
          return "1900000001".equals(merchantNo);
        }
      };
    }
    @Bean HexuMerchantOnboarding onboarding(HexuStore db, PaymentGateway gateway) {
      return new HexuMerchantOnboarding(db, gateway);
    }
    @Bean HexuOrderService orders(HexuStore db, HexuCatalogService catalog) {
      return new HexuOrderService(db, catalog);
    }
    @Bean HexuFinanceService finance(HexuStore db) { return new HexuFinanceService(db); }
    @Bean HexuCommands commands(HexuStore db) { return new HexuCommands(db); }
    @Bean HexuChannelInbox inbox(HexuStore db, HexuOrderService orders,
        HexuFinanceService finance) {
      return new HexuChannelInbox(db, orders, finance);
    }
  }

  @Autowired DataSource dataSource;
  @Autowired HexuStore db;
  @Autowired HexuMerchantOnboarding onboarding;
  @Autowired HexuOrderService orders;
  @Autowired HexuFinanceService finance;
  @Autowired HexuChannelInbox inbox;
  @Autowired HexuCommands commands;

  @BeforeEach
  void reset() {
    db.jdbc().execute("DROP ALL OBJECTS");
    ResourceDatabasePopulator seed = new ResourceDatabasePopulator(
        new ClassPathResource("db/hexu-schema.sql"),
        new ClassPathResource("db/hexu-seed.sql"));
    seed.setSqlScriptEncoding("UTF-8");
    seed.execute(dataSource);
    HexuTestJson.register(db.jdbc());
  }

  @Test
  void dealerOrderAndPaymentUseCompanyMerchantWithoutDealerOnboarding() {
    db.jdbc().update("update hx_shop set merchant_no=null,payment_ready=false where id=2");
    Actor buyer = new Actor(201, false, false);
    Map<String, Object> merchantStatus = onboarding.status(new Actor(101, false, false), 2);
    assertEquals("DIRECT", merchantStatus.get("paymentMode"));
    assertEquals(true, merchantStatus.get("paymentReady"));
    assertEquals(true, merchantStatus.get("sharedDirect"));
    BusinessException oldOrder = assertThrows(BusinessException.class,
        () -> onboarding.requireReady(2, "SANDBOX-ANXI"));
    assertEquals("订单收款方式已变更，请重新下单", oldOrder.getMessage());
    Map<String, Object> request = HexuOrderConsentFixture.accepted(db, 2,
        map("shopId", 2, "items", List.of(map("id", "stapler", "qty", 1)),
            "address", map("name", "合成收货人", "detail", "本地测试地址")));

    Map<String, Object> order = orders.create(buyer, 2, request);
    assertEquals("1900000001", str(order, "merchant_no"));
    Map<String, Object> line = db.one(
        "select snapshot_json from hx_order_line where order_id=?", order.get("id"));
    assertEquals("1900000001", db.parse(line.get("snapshot_json")).get("merchant"));

    Map<String, Object> payment = orders.payment(buyer, str(order, "id"));
    assertEquals("1900000001", str(payment, "merchant_no"));
    assertEquals("CREATED", str(payment, "status"));
  }

  @Test
  void paymentRoutePreservesDealerHistoryAndUsesCompanyMerchantForUnifiedOrders() {
    WechatPayGateway gateway = new WechatPayGateway(db, new ObjectMapper());
    ReflectionTestUtils.setField(gateway, "directId", "1900000001");
    assertFalse(gateway.partner(map("kind", "DEALER", "merchant_no", "1900000001")));
    assertTrue(gateway.partner(map("kind", "DEALER", "merchant_no", "1900000002")));
    assertFalse(gateway.partner(map("kind", "DIRECT", "merchant_no", "1900000002")));

    ReflectionTestUtils.setField(gateway, "directId", "");
    assertFalse(gateway.partner(map("kind", "DIRECT", "merchant_no", "1900000001")));
    assertTrue(gateway.partner(map("kind", "DEALER", "merchant_no", "1900000002")));
  }

  @Test
  void dealerPaymentAndRefundApplyOnceThroughCompanyMerchant() {
    db.jdbc().update("update hx_shop set merchant_no=null,payment_ready=false where id=2");
    Actor buyer = new Actor(201, false, false);
    Actor owner = new Actor(101, false, false);
    Map<String, Object> request = HexuOrderConsentFixture.accepted(db, 2,
        map("shopId", 2, "items", List.of(map("id", "stapler", "qty", 1)),
            "address", map("name", "合成收货人", "detail", "本地测试地址")));
    Map<String, Object> order = orders.create(buyer, 2, request);
    Map<String, Object> payment = orders.payment(buyer, str(order, "id"));
    assertEquals("1900000001", str(payment, "merchant_no"));
    String paymentId = str(payment, "id");
    long amount = number(payment, "amount");

    String paidEvent = inbox.accept(map("type", "PAYMENT_SUCCESS", "id", paymentId,
        "merchantNo", "1900000001", "amount", amount, "channelRef", "SIMULATED-PAY"));
    inbox.apply(paidEvent);
    assertEquals("APPLIED", ((Map<?, ?>) inbox.apply(paidEvent)).get("status"));
    assertEquals("PAID", str(db.one("select status from hx_order where id=?", order.get("id")), "status"));
    assertEquals(1, db.jdbc().queryForObject(
        "select count(*) from hx_ledger where reference_id=? and category='RECEIPT'",
        Integer.class, order.get("id")));
    assertThrows(BusinessException.class, () -> inbox.accept(map("type", "PAYMENT_SUCCESS",
        "id", paymentId, "merchantNo", "1900000001", "amount", amount + 1,
        "channelRef", "SIMULATED-PAY")));

    long lineId = number(db.one("select id from hx_order_line where order_id=?", order.get("id")), "id");
    Map<String, Object> refund = finance.refundApply(buyer, str(order, "id"),
        map("lineId", lineId, "qty", 1, "type", "REFUND_ONLY", "reason", "模拟售后"));
    String refundId = str(refund, "id");
    finance.refundReview(owner, refundId, map("approve", true));
    String refundEvent = inbox.accept(map("type", "REFUND_SUCCESS", "id", refundId,
        "merchantNo", "1900000001", "amount", number(refund, "amount"),
        "channelRef", "SIMULATED-REFUND"));
    inbox.apply(refundEvent);
    assertEquals("APPLIED", ((Map<?, ?>) inbox.apply(refundEvent)).get("status"));
    assertEquals("SUCCESS", str(db.one("select status from hx_refund where id=?", refundId), "status"));
    assertEquals("REFUNDED", str(db.one("select status from hx_order where id=?", order.get("id")), "status"));
    assertEquals(1, db.jdbc().queryForObject(
        "select count(*) from hx_ledger where reference_id=? and category='REFUND'",
        Integer.class, refundId));
  }

  @Test
  @SuppressWarnings("unchecked")
  void miniProgramHttpOrderPaymentAndRefundCompleteInLocalSandbox() throws Exception {
    db.jdbc().update("update hx_shop set merchant_no=null,payment_ready=false where id=2");
    db.jdbc().update("insert into hx_wechat_account(app_id,open_id,member_id) values(?,?,?)",
        "wx-local-test", "openid-local-buyer", 201);
    HexuIdentity identity = new HexuIdentity(db);
    String buyerToken = identity.session(201);
    String ownerToken = identity.session(101);
    HexuAppController app = new HexuAppController(identity, db, null, orders, finance,
        null, commands, null, null, null);
    HexuSandboxPaymentGateway channel = new HexuSandboxPaymentGateway(db, finance);
    HexuWechatController wechat = new HexuWechatController(
        new UnconfiguredWechatIdentityGateway(), channel, identity, db, null, orders, null);
    HexuDevController sandbox = new HexuDevController(identity, db, orders, finance);
    MockMvc http = MockMvcBuilders.standaloneSetup(app, wechat, sandbox)
        .setControllerAdvice(new HexuErrors()).build();

    Map<String, Object> request = HexuOrderConsentFixture.accepted(db, 2,
        map("shopId", 2, "items", List.of(map("id", "stapler", "qty", 1)),
            "address", map("name", "合成收货人", "detail", "本地测试地址")));
    Map<String, Object> quoted = (Map<String, Object>) jsonResponse(http.perform(
        post("/hexu/app/quote").header("Authorization", "Bearer " + buyerToken)
            .contentType("application/json").content(db.json(request)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    request.put("expectedTotal", quoted.get("total"));
    Map<String, Object> created = (Map<String, Object>) jsonResponse(http.perform(
        post("/hexu/app/commands/order-create")
            .header("Authorization", "Bearer " + buyerToken)
            .header("Idempotency-Key", "sandbox-order-001")
            .contentType("application/json").content(db.json(request)))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    String orderId = str(created, "id");
    assertEquals("1900000001", str(created, "merchant_no"));
    assertEquals("UNPAID", str(created, "status"));
    assertEquals(319, number(db.one("select available from hx_sku where shop_id=? and id=?", 2, "stapler"), "available"));

    Map<String, Object> pay = (Map<String, Object>) jsonResponse(http.perform(
        post("/hexu/app/wechat/payment/" + orderId)
            .header("Authorization", "Bearer " + buyerToken)
            .with(req -> { req.setRemoteAddr("127.0.0.1"); return req; }))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    assertEquals(true, pay.get("sandbox"));
    assertEquals("AWAITING_SANDBOX_CONFIRMATION", pay.get("status"));
    String paymentId = str(pay, "paymentId");
    assertEquals(number(created, "total"), number(pay, "amount"));
    assertEquals("1900000001", str(db.one(
        "select merchant_no from hx_payment where id=?", paymentId), "merchant_no"));
    for (int attempt = 0; attempt < 2; attempt++) {
      http.perform(post("/hexu/dev/payment/" + paymentId)
          .header("X-Hexu-Mp-Dev", "1")
          .with(req -> { req.setRemoteAddr("127.0.0.1"); return req; }))
          .andExpect(status().isOk());
    }
    assertEquals("PAID", str(db.one("select status from hx_order where id=?", orderId), "status"));
    assertEquals(1, db.jdbc().queryForObject(
        "select count(*) from hx_ledger where reference_id=? and category='RECEIPT'",
        Integer.class, orderId));

    long lineId = number(db.one("select id from hx_order_line where order_id=?", orderId), "id");
    Map<String, Object> refund = (Map<String, Object>) jsonResponse(http.perform(
        post("/hexu/app/commands/refund-apply")
            .header("Authorization", "Bearer " + buyerToken)
            .header("Idempotency-Key", "sandbox-refund-001")
            .contentType("application/json")
            .content(db.json(map("shopId", 2, "id", orderId, "lineId", lineId,
                "qty", 1, "type", "REFUND_ONLY", "reason", "模拟售后"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    String refundId = str(refund, "id");
    http.perform(post("/hexu/app/management/refund-review")
        .header("Authorization", "Bearer " + ownerToken)
        .header("Idempotency-Key", "sandbox-review-001")
        .contentType("application/json")
        .content(db.json(map("shopId", 2, "id", refundId, "approve", true))))
        .andExpect(status().isOk());
    for (int attempt = 0; attempt < 2; attempt++) {
      http.perform(post("/hexu/dev/refund/" + refundId)
          .header("X-Hexu-Mp-Dev", "1")
          .with(req -> { req.setRemoteAddr("127.0.0.1"); return req; }))
          .andExpect(status().isOk());
    }
    assertEquals("SUCCESS", str(db.one("select status from hx_refund where id=?", refundId), "status"));
    assertEquals("REFUNDED", str(db.one("select status from hx_order where id=?", orderId), "status"));
    assertEquals(1, db.jdbc().queryForObject(
        "select count(*) from hx_ledger where reference_id=? and category='REFUND'",
        Integer.class, refundId));
    assertEquals(1, db.jdbc().queryForObject(
        "select count(*) from hx_audit where action='SANDBOX_PAYMENT' and reference_id=?",
        Integer.class, paymentId));
    assertEquals(1, db.jdbc().queryForObject(
        "select count(*) from hx_audit where action='SANDBOX_REFUND' and reference_id=?",
        Integer.class, refundId));
  }

  private Map<String, Object> jsonResponse(String body) throws Exception {
    return new ObjectMapper().readValue(body, Map.class);
  }

  @Test
  void companyStatementIsVisibleOnlyToPlatformInUnifiedMode() {
    HexuChannelController channel = new HexuChannelController(null, db, null, null);
    ReflectionTestUtils.setField(channel, "realPaymentEnabled", true);
    ReflectionTestUtils.setField(channel, "allShopsDirect", true);
    ReflectionTestUtils.setField(channel, "companyMerchantNo", "1900000001");
    assertEquals("1900000001", channel.statementMerchant(new Actor(1, true, true), 2));
    assertThrows(BusinessException.class,
        () -> channel.statementMerchant(new Actor(101, false, false), 2));

    ReflectionTestUtils.setField(channel, "allShopsDirect", false);
    String shopMerchant = str(db.shop(2), "merchant_no");
    assertEquals(shopMerchant, channel.statementMerchant(new Actor(101, false, false), 2));
  }
}
