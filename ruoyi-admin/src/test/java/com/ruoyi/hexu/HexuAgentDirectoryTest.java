package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.number;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HexuAgentDirectoryTest {
    private final DriverManagerDataSource source=new DriverManagerDataSource(
        "jdbc:h2:mem:agentdirectory;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
    private final HexuStore db=new HexuStore(new JdbcTemplate(source),new ObjectMapper());
    private final HexuAgentDirectory directory=new HexuAgentDirectory(db);
    private final Actor owner=new Actor(101,false,false);

    @BeforeEach
    void reset(){
        db.jdbc().execute("DROP ALL OBJECTS");
        ResourceDatabasePopulator scripts=new ResourceDatabasePopulator(
            new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));
        scripts.setSqlScriptEncoding("UTF-8");
        scripts.execute(source);
    }

    @SuppressWarnings("unchecked")
    @Test
    void treePagesEachLevelAndPreservesAncestorDetails(){
        db.jdbc().update("insert into hx_agent(id,member_id,shop_id,parent_id,rank_no) values(5,202,2,1,1)");
        Map<String,Object> first=directory.children(owner,2,1,0,1);
        assertEquals(2,number(((List<Map<String,Object>>)first.get("items")).get(0),"id"));
        assertEquals(1,number(((List<Map<String,Object>>)first.get("items")).get(0),"childCount"));
        assertEquals(2L,first.get("nextCursor"));
        Map<String,Object> second=directory.children(owner,2,1,2,1);
        assertEquals(5,number(((List<Map<String,Object>>)second.get("items")).get(0),"id"));
        assertNull(second.get("nextCursor"));
        Map<String,Object> leaf=directory.detail(owner,2,4);
        assertEquals(4,((List<?>)leaf.get("ancestors")).size());
        assertEquals(0,leaf.get("descendantCount"));
        assertEquals(4,directory.detail(owner,2,1).get("descendantCount"));
        assertThrows(BusinessException.class,()->directory.detail(owner,2,10));
    }

    @SuppressWarnings("unchecked")
    @Test
    void operatingRegionsAreExplicitAuditedAndDoNotChangeRelationships(){
        String anxi="福建省 泉州市 安溪县";
        directory.setRegions(owner,2,3,Collections.singletonList(anxi),"分配经营区域");
        assertEquals(Collections.singletonList(anxi),directory.detail(owner,2,3).get("regions"));
        assertEquals(2,number(db.one("select parent_id from hx_agent where id=3"),"parent_id"));
        assertEquals(1,((List<Map<String,Object>>)directory.search(owner,2,"",anxi,0,50).get("items")).size());
        assertEquals(1,number(directory.regions(owner,2).get(0),"agentCount"));
        assertEquals(Set.of("region","agentCount"),directory.regions(owner,2).get(0).keySet());
        assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='AGENT_REGION_SET'",Integer.class));
        assertThrows(BusinessException.class,()->directory.setRegions(owner,2,3,Arrays.asList(anxi,anxi),"重复区域"));
        assertThrows(BusinessException.class,()->directory.setRegions(owner,2,3,Collections.singletonList("随意区域"),"非法区域"));
        assertEquals(Collections.singletonList(anxi),directory.detail(owner,2,3).get("regions"));
        directory.setRegions(owner,2,3,Collections.emptyList(),"撤销经营区域");
        assertEquals(Collections.emptyList(),directory.detail(owner,2,3).get("regions"));
        assertEquals(2,number(db.one("select parent_id from hx_agent where id=3"),"parent_id"));
    }

    @Test
    void shopAndRoleRestrictionsApplyToBothReadingAndEditing(){
        db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(555,2,'WAREHOUSE')");
        Actor warehouse=new Actor(555,false,true);
        assertThrows(BusinessException.class,()->directory.children(warehouse,2,0,0,50));
        assertThrows(BusinessException.class,()->directory.setRegions(warehouse,2,3,Collections.emptyList(),"清除区域"));
        assertThrows(BusinessException.class,()->directory.children(owner,2,10,0,50));
        assertThrows(BusinessException.class,()->directory.detail(owner,3,3));
    }

    @Test
    void crossShopParentNameRequiresActivePeerAuthorization(){
        db.jdbc().update("update hx_agent set parent_id=10 where id=4");
        Map<String,Object> before=directory.detail(owner,2,4);
        assertNull(before.get("externalParent"));
        db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values(?,?,?,?,?,?)",
            "PEER-directory",2,301,"peer_authorization","ACTIVE",
            db.json(Map.of("parentAgentId",10,"childAgentId",4)));
        Map<String,Object> after=directory.detail(owner,2,4);
        assertEquals(10,number((Map<String,Object>)after.get("externalParent"),"id"));
        assertEquals("公司运营",((Map<?,?>)after.get("externalParent")).get("name"));
    }

    @Test
    void mobileRoutesReturnTheTreeContractAndEnforceShopAccess() throws Exception {
        HexuIdentity identity=new HexuIdentity(db);
        MockMvc http=MockMvcBuilders.standaloneSetup(new HexuAppAgentTreeController(identity,directory))
            .setControllerAdvice(new HexuErrors()).build();
        String token="Bearer "+identity.session(owner.id);

        http.perform(get("/hexu/app/management/agent-tree/children")
                .header("Authorization",token).param("shopId","2").param("size","1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].name").value("A 总代理"))
            .andExpect(jsonPath("$.data.items[0].childCount").value(1));
        http.perform(get("/hexu/app/management/agent-tree/4")
                .header("Authorization",token).param("shopId","2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ancestors.length()").value(4))
            .andExpect(jsonPath("$.data.name").value("C1 云代理"))
            .andExpect(jsonPath("$.data.childCount").value(0))
            .andExpect(jsonPath("$.data.customerCount").value(2));
        http.perform(get("/hexu/app/management/agent-tree/4").param("shopId","2"))
            .andExpect(status().isUnauthorized());
        http.perform(get("/hexu/app/management/agent-tree/4")
                .header("Authorization",token).param("shopId","3"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void agentTeamRoutesExposeOnlyOwnBranch() throws Exception {
        db.jdbc().update("insert into hx_agent(id,member_id,shop_id,parent_id,rank_no) values(5,202,2,2,1)");
        HexuIdentity identity=new HexuIdentity(db);
        MockMvc http=MockMvcBuilders.standaloneSetup(new HexuAppAgentTeamController(identity,directory))
            .setControllerAdvice(new HexuErrors()).build();
        String agentToken="Bearer "+identity.session(103);

        http.perform(get("/hexu/app/agent-team/3")
                .header("Authorization",agentToken).param("shopId","2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("C 云代理"))
            .andExpect(jsonPath("$.data.childCount").value(1));
        http.perform(get("/hexu/app/agent-team/4")
                .header("Authorization",agentToken).param("shopId","2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("C1 云代理"));
        http.perform(get("/hexu/app/agent-team/children")
                .header("Authorization",agentToken).param("shopId","2").param("parentId","3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value(4));
        http.perform(get("/hexu/app/agent-team/2")
                .header("Authorization",agentToken).param("shopId","2"))
            .andExpect(status().isBadRequest());
        http.perform(get("/hexu/app/agent-team/5")
                .header("Authorization",agentToken).param("shopId","2"))
            .andExpect(status().isBadRequest());
        http.perform(get("/hexu/app/agent-team/children")
                .header("Authorization",agentToken).param("shopId","2").param("parentId","1"))
            .andExpect(status().isBadRequest());
        http.perform(get("/hexu/app/agent-team/3")
                .header("Authorization",agentToken).param("shopId","3"))
            .andExpect(status().isBadRequest());
        http.perform(get("/hexu/app/agent-team/3").param("shopId","2"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoutesSaveAndFilterRegionsWithStableJsonFields() throws Exception {
        HexuIdentity identity=new HexuIdentity(db){
            @Override public Actor admin(){return owner;}
        };
        MockMvc http=MockMvcBuilders.standaloneSetup(
                new HexuAdminAgentTreeController(identity,directory,new HexuCommands(db)))
            .setControllerAdvice(new HexuErrors()).build();
        String area="福建省 泉州市 安溪县";
        String body=db.json(Map.of("shopId",2,"regions",List.of(area),"reason","划分经营区域"));

        http.perform(post("/hexu/admin/agent-tree/3/regions")
                .header("Idempotency-Key","region-check-0001")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.regions[0]").value(area));
        http.perform(get("/hexu/admin/agent-tree/regions").param("shopId","2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].region").value(area))
            .andExpect(jsonPath("$.data[0].agentCount").value(1));
        http.perform(get("/hexu/admin/agent-tree/search")
                .param("shopId","2").param("region",area))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].id").value(3))
            .andExpect(jsonPath("$.data.items[0].childCount").value(1));
        http.perform(get("/hexu/admin/agent-tree/region-options")
                .param("shopId","2").param("query","安溪县"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0]").value(area));
        http.perform(post("/hexu/admin/agent-tree/3/regions")
                .header("Idempotency-Key","region-check-0001")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        http.perform(post("/hexu/admin/agent-tree/3/regions")
                .header("Idempotency-Key","region-check-0002")
                .contentType(MediaType.APPLICATION_JSON)
                .content(db.json(Map.of("shopId",2,"regions",area,"reason","格式检查"))))
            .andExpect(status().isBadRequest());
        assertEquals(1,db.jdbc().queryForObject(
            "select count(*) from hx_audit where action='AGENT_REGION_SET'",Integer.class));
    }
}
