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
import org.springframework.transaction.annotation.EnableTransactionManagement;

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
