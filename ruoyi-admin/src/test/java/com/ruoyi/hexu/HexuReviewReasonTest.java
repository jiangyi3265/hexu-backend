package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=HexuReviewReasonTest.Config.class)
class HexuReviewReasonTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:hexureviewreasontest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean org.springframework.transaction.PlatformTransactionManager tx(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean HexuStore db(JdbcTemplate jdbc,ObjectMapper mapper){return new HexuStore(jdbc,mapper);}
        @Bean HexuWorkflowService workflow(HexuStore db){return new HexuWorkflowService(db);}
    }
    @Autowired DataSource source;@Autowired HexuStore db;@Autowired HexuWorkflowService workflow;
    final Actor customer=new Actor(201,false,false),owner=new Actor(101,false,false);
    @BeforeEach void prepare(){
        db.jdbc().execute("DROP ALL OBJECTS");ResourceDatabasePopulator seed=new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));seed.setSqlScriptEncoding("UTF-8");seed.execute(source);
    }
    String pending(){return str(workflow.save(customer,2,"support",map("question","订单问题","message","真实本人留言","uploads",Collections.emptyList()),false),"id");}
    String repeat(String text,int count){return String.join("",Collections.nCopies(count,text));}
    void rejectsWithoutMutation(String id,Map<String,Object> request){
        Map<String,Object> before=db.one("select * from hx_document where id=?",id);String input=db.json(request);
        long audits=db.jdbc().queryForObject("select count(*) from hx_audit",Long.class),messages=db.jdbc().queryForObject("select count(*) from hx_message",Long.class);
        assertThrows(BusinessException.class,()->workflow.review(owner,id,request));
        assertEquals(before,db.one("select * from hx_document where id=?",id));assertEquals(input,db.json(request));
        assertEquals(audits,db.jdbc().queryForObject("select count(*) from hx_audit",Long.class));assertEquals(messages,db.jdbc().queryForObject("select count(*) from hx_message",Long.class));
    }
    @Test void allDecisionsRejectNonStringOverLimitAndIllegalControlsBeforeChangingRecord(){
        String id=pending();List<Object> invalid=new ArrayList<>(Arrays.asList(null,123,false,Collections.emptyList(),map("text","原因"),repeat("中",501),repeat("🌿",501)));
        for(int point=0;point<=0x9f;point++)if(Character.isISOControl(point)&&point!='\n'&&point!='\r'&&point!='\t')invalid.add("真实"+Character.toString((char)point)+"原因");
        invalid.add("\u0001真实原因");invalid.add("真实原因\u0085");
        for(String decision:Arrays.asList("APPROVED","REJECTED","SUPPLEMENT"))for(Object value:invalid)rejectsWithoutMutation(id,map("decision",decision,"reason",value));
    }
    @Test void rejectionAndSupplementNeedUnicodeNonblankTextAndRejectMissingReason(){
        String id=pending();
        for(String decision:Arrays.asList("REJECTED","SUPPLEMENT")){
            rejectsWithoutMutation(id,map("decision",decision));
            for(String value:Arrays.asList(""," ","\u3000","\u00a0","\u2003","\ufeff","\n\r\t\u3000\ufeff"))rejectsWithoutMutation(id,map("decision",decision,"reason",value));
        }
    }
    @Test void allDecisionsPreserveMultilineAndAcceptExactlyFiveHundredUnicodeCharacters(){
        String multiline=repeat("中",490)+"\n第二行\r\n\t末行字";assertEquals(500,multiline.codePointCount(0,multiline.length()));
        for(String decision:Arrays.asList("APPROVED","REJECTED","SUPPLEMENT")){
            String id=pending();Map<String,Object> before=db.one("select body_json from hx_document where id=?",id),request=map("decision",decision,"reason","\u3000\ufeff"+multiline+"\u00a0");String input=db.json(request);
            Map<String,Object> reviewed=workflow.review(owner,id,request);assertEquals(decision,reviewed.get("status"));assertEquals(multiline,reviewed.get("review_note"));
            assertEquals(before.get("body_json"),reviewed.get("body_json"));assertEquals(input,db.json(request));
        }
        // H2 VARCHAR length counts UTF-16 units; check supplementary-codepoint normalization independently.
        String emojis=repeat("🌿",500);Map<String,Object> canonical=ReflectionTestUtils.invokeMethod(HexuWorkflowService.class,"checkedReviewReason",map("reason",emojis),"APPROVED");
        assertNotNull(canonical);assertEquals(emojis,canonical.get("reason"));
    }
    @Test void approvedMissingOrExplicitEmptyReasonRemainAllowedAndNormalized(){
        for(Map<String,Object> request:Arrays.asList(map("decision","APPROVED"),map("decision","APPROVED","reason",""),map("decision","APPROVED","reason","\u3000\ufeff\n\r\t"))){
            String id=pending();String before=db.json(request);Map<String,Object> reviewed=workflow.review(owner,id,request);
            assertEquals("APPROVED",reviewed.get("status"));assertEquals("",reviewed.get("review_note"));assertEquals(before,db.json(request));
        }
    }
}
