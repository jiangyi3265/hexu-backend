package com.ruoyi.hexu;

import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=HexuOrderConsentTest.Config.class)
class HexuOrderConsentTest {
  @Configuration
  @EnableTransactionManagement
  static class Config {
    @Bean DataSource dataSource() {
      return new DriverManagerDataSource(
          "jdbc:h2:mem:orderconsent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Bean JdbcTemplate jdbc(DataSource dataSource) { return new JdbcTemplate(dataSource); }
    @Bean ObjectMapper json() { return new ObjectMapper(); }
    @Bean DataSourceTransactionManager tx(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
    @Bean HexuStore db(JdbcTemplate jdbc, ObjectMapper json) { return new HexuStore(jdbc, json); }
    @Bean HexuCatalogService catalog(HexuStore db) { return new HexuCatalogService(db); }
    @Bean HexuPolicies policies(HexuStore db) { return new HexuPolicies(db); }
    @Bean HexuOrderService orders(HexuStore db, HexuCatalogService catalog) {
      return new HexuOrderService(db, catalog);
    }
    @Bean HexuCommands commands(HexuStore db) { return new HexuCommands(db); }
  }

  @Autowired DataSource dataSource;
  @Autowired HexuStore db;
  @Autowired HexuPolicies policies;
  @Autowired HexuOrderService orders;
  @Autowired HexuCommands commands;
  private final Actor buyer = new Actor(201, false, false);
  private final Actor owner = new Actor(101, false, false);
  private final Actor platform = new Actor(1, true, true);

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

  private Map<String,Object> request() {
    return map("shopId", 2, "items", List.of(map("id", "stapler", "qty", 1)),
        "address", map("name", "合成收货人", "detail", "本地测试地址"));
  }

  private Map<String,Object> publish(long shop, String version) {
    return policies.publish(platform, shop, map("type", "PURCHASE_AGREEMENT", "version", version,
        "title", "购买协议", "content", "本协议仅用于自动化测试，明确订单商品、金额、配送及售后规则。"));
  }

  private Map<String,Object> accepted(Map<String,Object> body, Map<String,Object> policy) {
    body.put("orderConsent", true);
    body.put("agreementPolicyId", policy.get("id"));
    body.put("agreementVersion", policy.get("version"));
    return body;
  }

  private void assertNoOrderWrite() {
    assertEquals(0, db.jdbc().queryForObject("select count(*) from hx_order", Integer.class));
    assertEquals(0, db.jdbc().queryForObject(
        "select count(*) from hx_audit where action='ORDER_CREATE'", Integer.class));
    assertEquals(0, db.jdbc().queryForObject(
        "select count(*) from hx_policy_consent where policy_type='PURCHASE_AGREEMENT'", Integer.class));
    assertEquals(320, number(db.one("select available from hx_sku where shop_id=2 and id='stapler'"), "available"));
    assertEquals(0, number(db.one("select locked from hx_sku where shop_id=2 and id='stapler'"), "locked"));
  }

  @Test
  void missingFalseForgedAndStaleConsentDoNotCreateOrLock() {
    Map<String,Object> unpublished = accepted(request(), map("id", "POL-missing", "version", "purchase-v0"));
    assertThrows(BusinessException.class, () -> orders.create(buyer, 2, unpublished));
    Map<String,Object> policy = publish(2, "purchase-v1");
    assertThrows(BusinessException.class, () -> orders.create(buyer, 2, request()));
    Map<String,Object> falseConsent = accepted(request(), policy);
    falseConsent.put("orderConsent", false);
    assertThrows(BusinessException.class, () -> orders.create(buyer, 2, falseConsent));
    Map<String,Object> stringConsent = accepted(request(), policy);
    stringConsent.put("orderConsent", "true");
    assertThrows(BusinessException.class, () -> orders.create(buyer, 2, stringConsent));
    Map<String,Object> wrongPolicy = accepted(request(), policy);
    wrongPolicy.put("agreementPolicyId", "POL-forged");
    assertThrows(BusinessException.class, () -> orders.create(buyer, 2, wrongPolicy));
    Map<String,Object> otherShop = accepted(request(), publish(3, "purchase-v1"));
    assertThrows(BusinessException.class, () -> orders.create(buyer, 2, otherShop));
    Map<String,Object> stale = accepted(request(), policy);
    publish(2, "purchase-v2");
    assertThrows(BusinessException.class, () -> orders.create(buyer, 2, stale));
    assertNoOrderWrite();
  }

  @Test
  void currentConsentCreatesOnceAndKeepsVersionAudit() {
    Map<String,Object> policy = publish(2, "purchase-v1");
    Map<String,Object> body = accepted(request(), policy);
    String key = "ORDER-CONSENT-TEST-1";
    // 拒绝请求不得占用幂等键；补齐当前协议后才允许创建一次。
    Map<String,Object> missing = request();
    assertThrows(BusinessException.class, () -> commands.execute(buyer, key, "order-create", missing,
        () -> orders.create(buyer, 2, missing)));
    assertEquals(0, db.jdbc().queryForObject("select count(*) from hx_idempotency", Integer.class));
    Object first = commands.execute(buyer, key, "order-create", body,
        () -> orders.create(buyer, 2, body));
    Object replay = commands.execute(buyer, key, "order-create", body,
        () -> orders.create(buyer, 2, body));
    assertEquals(str((Map<String,Object>)first, "id"), str((Map<String,Object>)replay, "id"));
    publish(2, "purchase-v2");
    Object afterUpdate = commands.execute(buyer, key, "order-create", body,
        () -> { throw new AssertionError("合法旧版本重试不应重新建单"); });
    assertEquals(str((Map<String,Object>)first, "id"), str((Map<String,Object>)afterUpdate, "id"));
    db.jdbc().update("update hx_shop set status='INACTIVE' where id=2");
    Object afterShopClosed = commands.execute(buyer, key, "order-create", body,
        () -> { throw new AssertionError("商城停用后同键重试不应重新建单"); });
    assertEquals(str((Map<String,Object>)first, "id"), str((Map<String,Object>)afterShopClosed, "id"));
    assertEquals(1, db.jdbc().queryForObject("select count(*) from hx_order", Integer.class));
    assertEquals(319, number(db.one("select available from hx_sku where shop_id=2 and id='stapler'"), "available"));
    assertEquals(1, number(db.one("select locked from hx_sku where shop_id=2 and id='stapler'"), "locked"));
    Map<String,Object> consent = db.one("select * from hx_policy_consent where member_id=201 and shop_id=2 and policy_type='PURCHASE_AGREEMENT'");
    assertEquals("purchase-v1", consent.get("version"));
    assertEquals("ORDER_CREATE", consent.get("source"));
    Map<String,Object> audit = db.one("select after_json from hx_audit where action='ORDER_CREATE'");
    Map<String,Object> snapshot = db.parse(audit.get("after_json"));
    assertEquals(policy.get("id"), snapshot.get("agreementPolicyId"));
    assertEquals("purchase-v1", snapshot.get("agreementVersion"));
  }

  @Test
  void cachedLegacyOrderWithoutConsentIsRejectedWithoutReexecuting() {
    Map<String,Object> missing = request();
    String key = "LEGACY-NO-CONSENT-ORDER";
    // 模拟旧运行包写入的成功幂等缓存；修后不能把旧成功响应当作新请求通过。
    commands.execute(buyer, key, "order-create", missing,
        () -> map("id", "HXlegacyCachedOrder"));
    BusinessException rejected = assertThrows(BusinessException.class,
        () -> commands.execute(buyer, key, "order-create", missing,
            () -> { throw new AssertionError("不应重复建单"); }));
    assertEquals("请确认购买协议", rejected.getMessage());
    Map<String,Object> forged = accepted(request(), map("id", "POL-forged", "version", "forged-v1"));
    String forgedKey = "LEGACY-FORGED-CONSENT";
    commands.execute(buyer, forgedKey, "order-create", forged,
        () -> map("id", "HXlegacyForgedOrder"));
    BusinessException forgedRejected = assertThrows(BusinessException.class,
        () -> commands.execute(buyer, forgedKey, "order-create", forged,
            () -> { throw new AssertionError("旧伪造协议缓存不应重放"); }));
    assertEquals("历史订单响应无效", forgedRejected.getMessage());
    assertNoOrderWrite();
  }

  @Test
  void wholesalePurchaseKeepsItsExistingSeparateFlow() {
    Map<String,Object> body = map("shopId", 900,
        "items", List.of(map("id", "stapler", "qty", 48)),
        "address", map("name", "合成采购人", "detail", "本地测试仓"),
        "destinationShopId", 2, "directShip", true);
    String key = "WHOLESALE-CACHED-RETRY";
    Map<String,Object> order = (Map<String,Object>)commands.execute(owner, key,
        "order-create", body, () -> orders.create(owner, 900, body));
    Map<String,Object> replay = (Map<String,Object>)commands.execute(owner, key,
        "order-create", body, () -> { throw new AssertionError("批发重试不应重建"); });
    assertEquals("DIRECT_SHIP", order.get("order_type"));
    assertEquals(order.get("id"), replay.get("id"));
    assertEquals(0, db.jdbc().queryForObject(
        "select count(*) from hx_policy_consent where policy_type='PURCHASE_AGREEMENT'", Integer.class));
  }
}
