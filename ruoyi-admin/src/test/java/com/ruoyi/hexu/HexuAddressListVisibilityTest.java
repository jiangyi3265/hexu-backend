package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static com.ruoyi.hexu.HexuStore.map;
import static com.ruoyi.hexu.HexuStore.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HexuAddressListVisibilityTest {
    private final Actor member = new Actor(202, false, false);
    private HexuStore db;
    private HexuWorkflowService workflow;

    @BeforeEach void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:hexuaddresslist;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
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

    private String address(long shop, String name) {
        return str(workflow.save(member, shop, "address", map("name", name,
                "phone", "13800000202", "region", "浙江省 杭州市 西湖区",
                "detail", "测试路202号", "usage", "家", "consent", true), false), "id");
    }

    @Test void deletedAddressIsAbsentFromMemberApiAndOtherKindsKeepHistory() throws Exception {
        String active = address(2, "有效地址");
        String deleted = address(2, "待删除地址");
        String otherShop = address(3, "其他商城地址");
        workflow.remove(member, deleted);
        workflow.remove(member, deleted);
        assertEquals(1, db.jdbc().queryForObject(
                "select count(*) from hx_audit where action='ARCHIVE' and reference_id=?",
                Integer.class, deleted));
        db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values('DOCarchived-address',2,202,'address','ARCHIVED','{}')");
        db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values('DOCdeleted-support',2,202,'support','DELETED','{}')");

        assertEquals("DELETED", db.one("select status from hx_document where id=?", deleted).get("status"));
        List<Map<String,Object>> own = workflow.list(member, 2, "address", false);
        assertEquals(1, own.size());
        assertEquals(active, own.get(0).get("id"));
        assertEquals(otherShop, workflow.list(member, 3, "address", false).get(0).get("id"));
        assertTrue(workflow.list(new Actor(201, false, false), 2, "address", false)
                .stream().noneMatch(row -> active.equals(row.get("id")) || deleted.equals(row.get("id"))));
        assertEquals("DOCdeleted-support", workflow.list(member, 2, "support", false).get(0).get("id"));
        assertTrue(workflow.list(new Actor(101, false, false), 2, "address", true)
                .stream().anyMatch(row -> deleted.equals(row.get("id"))));

        HexuIdentity identity = new HexuIdentity(db);
        MockMvc http = MockMvcBuilders.standaloneSetup(new HexuAppController(
                identity, db, null, null, null, workflow, null, null, null, null))
                .setControllerAdvice(new HexuErrors()).build();
        String token = "Bearer " + identity.session(member.id);
        http.perform(get("/hexu/app/documents/address").header("Authorization", token).param("shopId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(active))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
        http.perform(get("/hexu/app/documents/address").header("Authorization", token).param("shopId", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(otherShop));
        http.perform(get("/hexu/app/documents/address").header("Authorization", "Bearer " + identity.session(201)).param("shopId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("ADDR201"))
                .andExpect(jsonPath("$.data.length()").value(1));
        http.perform(get("/hexu/app/documents/address").param("shopId", "2"))
                .andExpect(status().isUnauthorized());
    }
}
