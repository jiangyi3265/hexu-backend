package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;

class HexuCartReadModelTest {
    private HexuStore db;

    @BeforeEach void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:hexucartread;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        ResourceDatabasePopulator seed = new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"), new ClassPathResource("db/hexu-seed.sql"));
        seed.setSqlScriptEncoding("UTF-8");
        seed.execute(source);
        HexuTestJson.register(jdbc);
        db = new HexuStore(jdbc, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    @Test void legacyCartUsesOwnShopSkuAndOnlyPublicAsset() {
        db.jdbc().update("update hx_sku set status='INACTIVE' where shop_id=2 and id='paper'");
        db.jdbc().update("update hx_sku set name='其他商城商品' where shop_id=3 and id='paper'");
        String privateFile = "FILE" + String.join("", Collections.nCopies(32,"a"));
        db.jdbc().update("update hx_sku set status='INACTIVE',asset=? where shop_id=2 and id='cup'", privateFile);
        List<Map<String,Object>> lines = Arrays.asList(
                map("id", "paper", "qty", 1),
                map("id", "cup", "qty", 1),
                map("id", "missing", "qty", 1),
                map("id", "oil", "snapshot", map("name", "原购物车名称", "spec", "原规格", "asset", "oil")));
        List<Map<String,Object>> carts = Collections.singletonList(map("body", map("items", lines)));
        Map<String,Object> publicAssets = new HexuUiConfigService(new ObjectMapper()).defaultAssets();
        new HexuCartReadModel(db, publicAssets).withLegacySnapshots(2, carts);

        Map<String,Object> paper = (Map<String,Object>)lines.get(0).get("snapshot");
        assertEquals("清风 抽取式面巾纸", paper.get("name"));
        assertEquals("原木清香 / 6包", paper.get("spec"));
        assertEquals("paper", paper.get("asset"));
        Map<String,Object> cup = (Map<String,Object>)lines.get(1).get("snapshot");
        assertEquals("禾序 316不锈钢保温杯", cup.get("name"));
        assertEquals("", cup.get("asset"));
        assertFalse(lines.get(2).containsKey("snapshot"));
        assertEquals("原购物车名称", ((Map<String,Object>)lines.get(3).get("snapshot")).get("name"));
        assertTrue(new HexuCatalogService(db).products(new Actor(201,false,false),2,false)
                .stream().noneMatch(product -> "paper".equals(product.get("id")) || "cup".equals(product.get("id"))));
    }
}
