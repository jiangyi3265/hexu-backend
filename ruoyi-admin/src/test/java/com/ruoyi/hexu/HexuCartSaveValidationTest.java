package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.ruoyi.hexu.HexuStore.map;
import static com.ruoyi.hexu.HexuStore.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HexuCartSaveValidationTest {
    private final Actor customer = new Actor(201, false, false);
    private HexuStore db;
    private HexuWorkflowService workflow;

    @BeforeEach void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:hexucartsave;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        ResourceDatabasePopulator seed = new ResourceDatabasePopulator(
                new ClassPathResource("db/hexu-schema.sql"), new ClassPathResource("db/hexu-seed.sql"));
        seed.setSqlScriptEncoding("UTF-8");
        seed.execute(source);
        HexuTestJson.register(jdbc);
        db = new HexuStore(jdbc, new ObjectMapper());
        workflow = new HexuWorkflowService(db);
    }

    private Map<String,Object> line(String id, Object qty) {
        return map("id", id, "qty", qty, "selected", true,
                "snapshot", map("name", "原购物车商品", "spec", "原规格", "asset", "paper"));
    }

    private Map<String,Object> save(String id, List<?> items) {
        return workflow.save(customer, 2, "cart", map("id", id, "items", items), false);
    }

    private long audits() {
        return db.jdbc().queryForObject("select count(*) from hx_audit where action='SAVE_cart'", Long.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> savedLines(String id) {
        return (List<Map<String,Object>>)db.parse(db.one(
                "select body_json from hx_document where id=?", id).get("body_json")).get("items");
    }

    @Test void rejectsZeroFractionOverstockAndDuplicatesBeforePersistence() {
        assertThrows(BusinessException.class,
                () -> save("", Collections.singletonList(line("paper", 0))));
        assertEquals(0L, db.jdbc().queryForObject("select count(*) from hx_document where kind='cart'", Long.class));
        String id = str(save("", Collections.singletonList(line("paper", 1))), "id");
        long before = audits();
        for (Object invalid : Arrays.asList(0, -1, 1.5, 49, 100001, "1", null)) {
            assertThrows(BusinessException.class,
                    () -> save(id, Collections.singletonList(line("paper", invalid))), String.valueOf(invalid));
        }
        assertThrows(BusinessException.class,
                () -> save(id, Arrays.asList(line("paper", 1), line("paper", 1))));
        assertEquals(before, audits());
        assertEquals(1, savedLines(id).size());
        assertEquals(1, HexuStore.number(savedLines(id).get(0), "qty"));
        assertEquals(0L, db.jdbc().queryForObject("select count(*) from hx_order", Long.class));
    }

    @Test void preservesMemberAndShopScopeAndRejectsNewInactiveSku() {
        String id = str(save("", Collections.singletonList(line("paper", 1))), "id");
        long before = audits();
        db.jdbc().update("update hx_sku set id='cross-only' where shop_id=3 and id='stapler'");
        db.jdbc().update("update hx_sku set status='INACTIVE' where shop_id=2 and id='cup'");
        assertThrows(BusinessException.class,
                () -> save(id, Collections.singletonList(line("cross-only", 1))));
        assertThrows(BusinessException.class,
                () -> save(id, Collections.singletonList(line("cup", 1))));
        assertThrows(BusinessException.class,
                () -> workflow.save(new Actor(202, false, false), 2, "cart",
                        map("id", id, "items", Collections.emptyList()), false));
        assertThrows(BusinessException.class,
                () -> workflow.save(customer, 3, "cart",
                        map("id", id, "items", Collections.emptyList()), false));
        assertThrows(BusinessException.class,
                () -> workflow.save(customer, 3, "cart",
                        map("items", Collections.singletonList(line("paper", 1))), false));
        assertEquals(before, audits());
        assertEquals("paper", savedLines(id).get(0).get("id"));
    }

    @Test void existingUnavailableLineCanBeRecalledDeselectedAndRemoved() {
        String id = str(save("", Collections.singletonList(line("paper", 1))), "id");
        db.jdbc().update("update hx_sku set status='INACTIVE' where shop_id=2 and id='paper'");
        Map<String,Object> unchanged = line("paper", 1);
        unchanged.put("selected", false);
        save(id, Collections.singletonList(unchanged));
        assertEquals(false, savedLines(id).get(0).get("selected"));
        assertEquals("原购物车商品",
                ((Map<?,?>)savedLines(id).get(0).get("snapshot")).get("name"));
        long before = audits();
        assertThrows(BusinessException.class,
                () -> save(id, Collections.singletonList(line("paper", 2))));
        assertEquals(before, audits());
        save(id, Collections.emptyList());
        assertEquals(0, savedLines(id).size());
    }

    @Test void stockDropRejectsUnchangedOverstockUntilLineIsRemoved() {
        db.jdbc().update("update hx_sku set available=5 where shop_id=2 and id='paper'");
        String id = str(save("", Collections.singletonList(line("paper", 2))), "id");
        db.jdbc().update("update hx_sku set available=1 where shop_id=2 and id='paper'");
        long before = audits();
        assertThrows(BusinessException.class,
                () -> save(id, Collections.singletonList(line("paper", 2))));
        assertThrows(BusinessException.class,
                () -> save(id, Collections.singletonList(line("paper", 3))));
        assertEquals(before, audits());
        assertEquals(2, HexuStore.number(savedLines(id).get(0), "qty"));
        save(id, Collections.singletonList(line("paper", 1)));
        assertEquals(1, HexuStore.number(savedLines(id).get(0), "qty"));
        assertEquals(0L, db.jdbc().queryForObject("select count(*) from hx_inventory_log", Long.class));
    }
}
