package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=HexuReviewTest.Config.class)
@TestPropertySource(properties="hexu.fulfillment.require-pick=false")
class HexuReviewTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:hexureviewtest;MODE=MySQL;IGNORECASE=TRUE;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean org.springframework.transaction.PlatformTransactionManager tx(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean HexuStore store(JdbcTemplate jdbc,ObjectMapper mapper){return new HexuStore(jdbc,mapper);}
        @Bean HexuCatalogService catalog(HexuStore db){return new HexuCatalogService(db);}
        @Bean HexuOrderService orders(HexuStore db,HexuCatalogService catalog){return new HexuOrderService(db,catalog);}
        @Bean HexuWorkflowService workflow(HexuStore db){return new HexuWorkflowService(db);}
    }
    @Autowired DataSource source;@Autowired HexuStore db;@Autowired HexuOrderService orders;@Autowired HexuWorkflowService workflow;
    final Actor customer=new Actor(201,false,false),other=new Actor(202,false,false),owner=new Actor(101,false,false);
    HexuAttachmentController attachments;byte[] png;
    @BeforeEach void prepare()throws Exception{
        db.jdbc().execute("DROP ALL OBJECTS");ResourceDatabasePopulator seed=new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));seed.setSqlScriptEncoding("UTF-8");seed.execute(source);
        HexuTestJson.register(db.jdbc());
        attachments=new HexuAttachmentController(new HexuIdentity(db),db);ReflectionTestUtils.setField(attachments,"secret","review-test-encryption-at-least-32-characters");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);png=bytes.toByteArray();
    }
    MockHttpServletRequest request(Actor actor){MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+new HexuIdentity(db).session(actor.id));return request;}
    String upload(Actor actor,long shop,String purpose)throws Exception{return str((Map<String,Object>)((Map<?,?>)attachments.upload(request(actor),shop,purpose,new MockMultipartFile("file","review.png","image/png",png))).get("data"),"id");}
    String order(boolean completed){
        String id=str(orders.create(customer,2,HexuOrderConsentFixture.accepted(db,2,map("items",Arrays.asList(map("id","stapler","qty",1)),"address",map("name","测试会员","detail","测试地址")))),"id");
        if(completed){Map<String,Object> payment=orders.payment(customer,id);orders.paymentConfirmed(str(payment,"id"),str(payment,"merchant_no"),number(payment,"amount"),"REVIEW-PAY-"+id);orders.ship(owner,id,map("carrier","顺丰","tracking","SF123456789"));orders.receive(customer,id);}return id;
    }
    Map<String,Object> original(String order,Object... extra){Map<String,Object> body=map("orderId",order,"skuId","stapler","rating",5,"content","实际购买体验良好","anonymous",false,"uploads",Collections.emptyList());body.putAll(map(extra));return body;}
    Map<String,Object> append(String parent,Object... extra){Map<String,Object> body=map("parentId",parent,"skuId","stapler","content","使用一段时间后的真实体验","uploads",Collections.emptyList());body.putAll(map(extra));return body;}
    String review(String order,Object... extra){return str(workflow.save(customer,2,"review",original(order,extra),false),"id");}
    String approveOriginal(){String id=review(order(true));approve(id);return id;}
    String submitAppend(String parent,Object... extra){return str(workflow.save(customer,2,"review_append",append(parent,extra),false),"id");}
    void approve(String id){workflow.review(owner,id,map("decision","APPROVED"));}
    Map<String,Object> mine(){return workflow.reviews(customer,2,"stapler").get(0);}

    @Test void reviewAndAppendDecisionNotificationsAreSystemAndExcludePrivateTextAndReviewReason(){
        for(String kind:Arrays.asList("review","review_append"))for(String decision:Arrays.asList("APPROVED","REJECTED","SUPPLEMENT")){
            String content="不得复制到站内通知的真实购买体验",reason="不得复制到站内通知的私密审核意见";
            String id="review".equals(kind)?review(order(true),"content",content):submitAppend(approveOriginal(),"content",content);
            workflow.review(owner,id,map("decision",decision,"reason",reason));List<Map<String,Object>> notifications=db.jdbc().queryForList("select * from hx_message where reference_id=?",id);assertEquals(1,notifications.size());Map<String,Object> message=notifications.get(0);
            assertEquals("SYSTEM",message.get("event_type"));assertEquals(HexuWorkflowService.reviewNotificationTitle(kind,decision),message.get("title"));assertEquals(message.get("title"),message.get("body_text"));assertEquals(customer.id,number(message,"member_id"));assertEquals(2,number(message,"shop_id"));
            assertFalse(db.json(message).contains(content));assertFalse(db.json(message).contains(reason));
        }
    }
    @Test void originalRequiresOwnCompletedOrderAndActualSku(){
        String unpaid=order(false);assertThrows(BusinessException.class,()->review(unpaid));String paid=order(true);
        assertThrows(BusinessException.class,()->workflow.save(other,2,"review",original(paid),false));
        assertThrows(BusinessException.class,()->workflow.save(customer,3,"review",original(paid),false));
        assertThrows(BusinessException.class,()->review(paid,"skuId","cup"));assertThrows(BusinessException.class,()->review(paid,"skuId",2));
        String id=review(paid);assertTrue(workflow.reviews(2,"stapler").isEmpty());approve(id);assertEquals(id,mine().get("id"));
        db.jdbc().update("update hx_order set refunded=1 where id=?",paid);assertFalse(bool(mine(),"appendAllowed"));assertThrows(BusinessException.class,()->submitAppend(id));
    }
    @Test void originalAndAppendTextRatingAndImageFieldsHaveStrictBounds()throws Exception{
        String paid=order(true),image=upload(customer,2,"REVIEW"),foreign=upload(other,2,"REVIEW"),cross=upload(customer,3,"REVIEW"),privateImage=upload(customer,2,"RETURN");
        List<Map<String,Object>> invalid=Arrays.asList(map("content"," "),map("content",12),map("content","x\u0000y"),map("content",String.join("",Collections.nCopies(501,"🌿"))),map("rating",0),map("rating",6),map("rating",4.5),map("uploads","not-list"),map("uploads",Arrays.asList(image,image)),map("uploads",Arrays.asList(foreign)),map("uploads",Arrays.asList(cross)),map("uploads",Arrays.asList(privateImage)),map("uploads",Arrays.asList(1)),map("uploads",Arrays.asList("missing")));
        for(Map<String,Object> change:invalid){Map<String,Object> body=original(paid);body.putAll(change);assertThrows(BusinessException.class,()->workflow.save(customer,2,"review",body,false),change.toString());}
        String parent=review(paid,"content",String.join("",Collections.nCopies(500,"🌿")),"uploads",Arrays.asList(image));approve(parent);
        for(Map<String,Object> change:invalid)if(!change.containsKey("rating")){Map<String,Object> body=append(parent);body.putAll(change);assertThrows(BusinessException.class,()->workflow.save(customer,2,"review_append",body,false),change.toString());}
        List<String> ten=new ArrayList<>();for(int i=0;i<10;i++)ten.add(upload(customer,2,"REVIEW"));assertThrows(BusinessException.class,()->submitAppend(parent,"uploads",ten));
    }
    @Test void unicodeBlankAndIllegalControlsRejectOnSaveAndNewApproval(){
        String paid=order(true);
        List<String> invalid=new ArrayList<>(Arrays.asList("\u3000","\u00a0","\u2003","\ufeff"," \u3000\u00a0\ufeff\n\r\t "));
        for(int point=0;point<=0x9f;point++)if(Character.isISOControl(point)&&point!='\n'&&point!='\r'&&point!='\t')
            invalid.add("真实"+Character.toString((char)point)+"评价");
        invalid.add("\u0001真实评价");invalid.add("真实评价\u0085");
        for(String value:invalid)assertThrows(BusinessException.class,()->review(paid,"content",value));
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='review'",Integer.class));
        String parent=review(paid);approve(parent);long points=number(db.pointAccount(0,201),"available");
        for(String value:invalid){
            assertThrows(BusinessException.class,()->submitAppend(parent,"content",value));
            // Previously stored pending content is checked only when an approval is attempted.
            for(String kind:Arrays.asList("review","review_append")){
                String document=id("DOC");Map<String,Object> body="review".equals(kind)?original(paid,"content",value):append(parent,"orderId",paid,"content",value);
                db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values(?,2,201,?,'PENDING',?)",document,kind,db.json(body));
                assertThrows(BusinessException.class,()->approve(document));
                Map<String,Object> unchanged=db.one("select status,body_json from hx_document where id=?",document);
                assertEquals("PENDING",unchanged.get("status"));assertEquals(value,db.parse(unchanged.get("body_json")).get("content"));
            }
        }
        assertEquals(points,number(db.pointAccount(0,201),"available"));assertEquals(Collections.emptyList(),mine().get("appends"));
    }
    @Test void multilineReviewAndAppendPreserveAllowedWhitespaceAndUnicodeCount(){
        String content=String.join("",Collections.nCopies(490,"🌿"))+"\n第二行\r\n\t末行",paid=order(true);
        assertEquals(499,content.codePointCount(0,content.length()));
        String parent=review(paid,"content","\u3000\ufeff"+content+"\u00a0");
        assertEquals(content,db.parse(db.one("select body_json from hx_document where id=?",parent).get("body_json")).get("content"));
        approve(parent);assertEquals(content,mine().get("content"));
        String child=submitAppend(parent,"content","\u00a0"+content+"\ufeff\u3000");approve(child);
        assertEquals(content,((List<Map<String,Object>>)mine().get("appends")).get(0).get("content"));
        String boundary=content+"🌿";assertEquals(500,boundary.codePointCount(0,boundary.length()));
        String otherOrder=order(true);assertNotNull(review(otherOrder,"content",boundary));
        assertThrows(BusinessException.class,()->review(order(true),"content",boundary+"🌿"));
    }
    @Test void publicImageRequiresExplicitApprovedReviewBinding()throws Exception{
        String image=upload(customer,2,"REVIEW"),unbound=upload(customer,2,"REVIEW"),privateImage=upload(customer,2,"RETURN"),id=review(order(true),"uploads",Arrays.asList(image),"irrelevantFile",unbound);
        assertThrows(BusinessException.class,()->attachments.publishedReview(image));assertThrows(BusinessException.class,()->attachments.publishedReview(unbound));
        approve(id);ResponseEntity<byte[]> response=attachments.publishedReview(image);assertArrayEquals(png,response.getBody());assertEquals("image/png",response.getHeaders().getContentType().toString());assertEquals("no-store",response.getHeaders().getCacheControl());assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals(Arrays.asList("/hexu/app/attachments/review/"+image),mine().get("images"));assertThrows(BusinessException.class,()->attachments.publishedReview(unbound));assertThrows(BusinessException.class,()->attachments.publishedReview(privateImage));
        Map<String,Object> stored=db.parse(db.one("select body_json from hx_document where id=?",id).get("body_json"));assertFalse(stored.containsKey("irrelevantFile"));
        db.jdbc().update("update hx_document set status='REJECTED' where id=?",id);assertThrows(BusinessException.class,()->attachments.publishedReview(image));
    }
    @Test void pendingAppendDoesNotHideOriginalAndApprovalPublishesWithoutPoints()throws Exception{
        String parent=approveOriginal(),image=upload(customer,2,"REVIEW");long points=number(db.pointAccount(0,201),"available"),rewardLogs=db.jdbc().queryForObject("select count(*) from hx_point_log where kind='REVIEW_REWARD'",Long.class);
        String child=submitAppend(parent,"uploads",Arrays.asList(image),"content","审核中的追加文本");
        assertEquals(parent,mine().get("id"));assertEquals("APPROVED",db.one("select status from hx_document where id=?",parent).get("status"));assertEquals(Collections.emptyList(),mine().get("appends"));assertFalse(bool(mine(),"appendAllowed"));assertEquals("PENDING",mine().get("appendStatus"));assertEquals(child,mine().get("appendId"));assertThrows(BusinessException.class,()->attachments.publishedReview(image));
        approve(child);List<Map<String,Object>> visible=(List<Map<String,Object>>)mine().get("appends");assertEquals(1,visible.size());assertEquals("审核中的追加文本",visible.get(0).get("content"));assertEquals(Arrays.asList("/hexu/app/attachments/review/"+image),visible.get(0).get("images"));assertArrayEquals(png,attachments.publishedReview(image).getBody());
        assertEquals(points,number(db.pointAccount(0,201),"available"));assertEquals(rewardLogs,db.jdbc().queryForObject("select count(*) from hx_point_log where kind='REVIEW_REWARD'",Long.class));assertThrows(BusinessException.class,()->approve(child));assertThrows(BusinessException.class,()->submitAppend(parent));
    }
    @Test void appendRequiresOwnApprovedParentMatchingSkuAndOrder(){
        String firstOrder=order(true),pending=review(firstOrder);assertThrows(BusinessException.class,()->submitAppend(pending));approve(pending);
        assertThrows(BusinessException.class,()->workflow.save(other,2,"review_append",append(pending),false));assertThrows(BusinessException.class,()->workflow.save(customer,3,"review_append",append(pending),false));
        assertThrows(BusinessException.class,()->submitAppend(pending,"skuId","cup"));assertThrows(BusinessException.class,()->submitAppend(pending,"orderId",order(true)));assertThrows(BusinessException.class,()->submitAppend("missing"));
        String second=approveOriginal(),child=submitAppend(pending);workflow.review(owner,child,map("decision","SUPPLEMENT","reason","请补充说明"));
        assertThrows(BusinessException.class,()->workflow.save(customer,2,"review_append",append(second,"id",child),false));
        Map<String,Object> retry=workflow.save(customer,2,"review_append",append(pending,"id",child,"content","补充说明"),false);assertEquals(child,retry.get("id"));assertEquals("PENDING",retry.get("status"));
    }
    @Test void duplicatesAreBlockedAndRejectedOriginalAndAppendMayRetry(){
        String paid=order(true),parent=review(paid);assertThrows(BusinessException.class,()->review(paid));assertThrows(BusinessException.class,()->review(paid,"id",parent));
        workflow.review(owner,parent,map("decision","SUPPLEMENT","reason","请补充评价"));assertThrows(BusinessException.class,()->review(paid));review(paid,"id",parent);workflow.review(owner,parent,map("decision","REJECTED","reason","请重新提交"));
        String retried=review(paid);approve(retried);assertThrows(BusinessException.class,()->review(paid,"id",parent));assertThrows(BusinessException.class,()->review(paid));
        String child=submitAppend(retried);assertThrows(BusinessException.class,()->submitAppend(retried));workflow.review(owner,child,map("decision","REJECTED","reason","请重新补充"));assertTrue(bool(mine(),"appendAllowed"));assertEquals("REJECTED",mine().get("appendStatus"));String next=submitAppend(retried);approve(next);assertThrows(BusinessException.class,()->submitAppend(retried,"id",child));
    }
    @Test void concurrencyCannotCreateDuplicateOriginalOrAppend()throws Exception{
        String paid=order(true);assertOneConcurrentSuccess(()->review(paid));String parent=str(db.one("select id from hx_document where kind='review'"),"id");approve(parent);assertOneConcurrentSuccess(()->submitAppend(parent));
        assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_document where kind='review'",Integer.class));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_document where kind='review_append'",Integer.class));
    }
    void assertOneConcurrentSuccess(Callable<String> action)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);try{List<Future<Boolean>> attempts=new ArrayList<>();for(int i=0;i<2;i++)attempts.add(pool.submit(()->{start.await();try{action.call();return true;}catch(BusinessException e){return false;}}));start.countDown();int successful=0;for(Future<Boolean> attempt:attempts)if(attempt.get(10,TimeUnit.SECONDS))successful++;assertEquals(1,successful);}finally{pool.shutdownNow();}
    }
    @Test void publicReviewsNeverRevealOthersOrderOrPendingAppendOrIdentity(){
        String parent=review(order(true),"anonymous",true);approve(parent);submitAppend(parent,"content","隐私审核文本");
        for(Actor requester:Arrays.asList(null,other)){
            Map<String,Object> publicReview=workflow.reviews(requester,2,"stapler").get(0);assertEquals("匿名用户",publicReview.get("name"));assertFalse(bool(publicReview,"mine"));assertFalse(bool(publicReview,"appendAllowed"));
            for(String key:Arrays.asList("orderId","member_id","memberId","phone","uploads","body_json","appendStatus","appendId"))assertFalse(publicReview.containsKey(key),key);assertFalse(db.json(publicReview).contains("隐私审核文本"));
        }
        assertTrue(bool(mine(),"mine"));assertNotNull(mine().get("orderId"));assertTrue(workflow.list(other,2,"review",false).isEmpty());assertTrue(workflow.list(other,2,"review_append",false).isEmpty());
    }
    @Test void parentMustRemainApprovedForAppendImagesToBePublic()throws Exception{
        String parent=approveOriginal(),image=upload(customer,2,"REVIEW"),child=submitAppend(parent,"uploads",Arrays.asList(image));approve(child);assertArrayEquals(png,attachments.publishedReview(image).getBody());
        db.jdbc().update("update hx_document set status='REJECTED' where id=?",parent);assertTrue(workflow.reviews(2,"stapler").isEmpty());assertThrows(BusinessException.class,()->attachments.publishedReview(image));
    }
    @Test void managementReadsAndApprovalHonorShopAndRole(){
        String parent=approveOriginal(),child=submitAppend(parent);db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(700,2,'SUPPORT'),(701,2,'WAREHOUSE'),(702,3,'OWNER'),(703,2,'OWNER')");
        Actor support=new Actor(700,false,true),warehouse=new Actor(701,false,true),cross=new Actor(702,false,true),staffOwner=new Actor(703,false,true);
        assertEquals(child,workflow.list(support,2,"review_append",true).get(0).get("id"));assertThrows(BusinessException.class,()->workflow.list(warehouse,2,"review_append",true));assertThrows(BusinessException.class,()->workflow.list(cross,2,"review_append",true));
        assertThrows(BusinessException.class,()->workflow.review(support,child,map("decision","APPROVED")));assertThrows(BusinessException.class,()->workflow.review(cross,child,map("decision","APPROVED")));assertEquals("APPROVED",workflow.review(staffOwner,child,map("decision","APPROVED")).get("status"));
        assertThrows(BusinessException.class,()->workflow.save(staffOwner,2,"review_append",append(parent),true));
    }
    @Test void approvalRechecksImagePurposePurchaseAndDuplicateRewards()throws Exception{
        String paid=order(true),image=upload(customer,2,"REVIEW"),parent=review(paid,"uploads",Arrays.asList(image));db.jdbc().update("update hx_attachment set purpose='RETURN' where id=?",image);assertThrows(BusinessException.class,()->approve(parent));
        db.jdbc().update("update hx_attachment set purpose='REVIEW' where id=?",image);db.jdbc().update("update hx_order set refunded=1 where id=?",paid);assertThrows(BusinessException.class,()->approve(parent));db.jdbc().update("update hx_order set refunded=0 where id=?",paid);approve(parent);
        long before=number(db.pointAccount(0,201),"available");String duplicate=id("DOC");db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values(?,2,201,'review','PENDING',?)",duplicate,db.json(original(paid)));assertThrows(BusinessException.class,()->approve(duplicate));assertEquals(before,number(db.pointAccount(0,201),"available"));
        String child=submitAppend(parent);db.jdbc().update("update hx_document set status='REJECTED' where id=?",parent);assertThrows(BusinessException.class,()->approve(child));
    }
    @Test void reviewsHttpSupportsPublicAndAuthenticatedContractAndPublicImageGate()throws Exception{
        String image=upload(customer,2,"REVIEW"),parent=review(order(true),"uploads",Arrays.asList(image));approve(parent);
        HexuIdentity identity=new HexuIdentity(db);MockMvc http=MockMvcBuilders.standaloneSetup(new HexuAppController(identity,db,null,null,null,workflow,null,null,null,null),attachments).setControllerAdvice(new HexuErrors()).build();
        http.perform(get("/hexu/app/reviews").param("shopId","2").param("skuId","stapler")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(parent)).andExpect(jsonPath("$.data[0].mine").value(false)).andExpect(jsonPath("$.data[0].orderId").doesNotExist());
        http.perform(get("/hexu/app/reviews").header("Authorization","Bearer "+identity.session(customer.id)).param("shopId","2").param("skuId","stapler")).andExpect(jsonPath("$.data[0].mine").value(true)).andExpect(jsonPath("$.data[0].appendAllowed").value(true)).andExpect(jsonPath("$.data[0].orderId").isString());
        http.perform(get("/hexu/app/reviews").header("Authorization","Bearer invalid").param("shopId","2").param("skuId","stapler")).andExpect(status().isUnauthorized());
        http.perform(get("/hexu/app/attachments/review/"+image)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().bytes(png));
        http.perform(get("/hexu/app/attachments/review/"+upload(customer,2,"REVIEW"))).andExpect(status().isBadRequest());
    }

    void legacyDocument(String id,String kind,String status,Map<String,Object> body,String time){
        db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json,created_at,updated_at) values(?,2,201,?,?,?,?,?)",id,kind,status,db.json(body),time,time);
    }
    Map<String,Object> context(String order,String parent){return new HexuReviews(db).context(customer,2,order,"stapler",parent);}

    @Test void originalSupplementUpdatesTextRatingAndImagesOnSameIdAndContextGet()throws Exception{
        String paid=order(true),oldImage=upload(customer,2,"REVIEW"),newImage=upload(customer,2,"REVIEW"),id=review(paid,"content","初次内容","rating",5,"uploads",Arrays.asList(oldImage));
        workflow.review(owner,id,map("decision","SUPPLEMENT","reason","请补使用细节"));
        Map<String,Object> changed=workflow.save(customer,2,"review",original(paid,"id",id,"content","补充后的新内容🌿","rating",2,"uploads",Arrays.asList(newImage)),false);
        assertEquals(id,changed.get("id"));assertEquals("PENDING",changed.get("status"));assertTrue(workflow.reviews(2,"stapler").isEmpty());
        Map<String,Object> submission=(Map<String,Object>)context(paid,null).get("submission"),body=(Map<String,Object>)submission.get("body");assertEquals("PENDING",submission.get("status"));assertEquals("补充后的新内容🌿",body.get("content"));assertEquals(2,number(body,"rating"));assertEquals(Arrays.asList(newImage),body.get("uploads"));
        HexuIdentity identity=new HexuIdentity(db);MockMvc http=MockMvcBuilders.standaloneSetup(new HexuAppController(identity,db,null,null,null,workflow,null,null,null,null)).setControllerAdvice(new HexuErrors()).build();
        http.perform(get("/hexu/app/review-context").header("Authorization","Bearer "+identity.session(customer.id)).param("shopId","2").param("orderId",paid).param("skuId","stapler")).andExpect(status().isOk()).andExpect(jsonPath("$.data.submission.id").value(id)).andExpect(jsonPath("$.data.submission.status").value("PENDING")).andExpect(jsonPath("$.data.submission.body.content").value("补充后的新内容🌿")).andExpect(jsonPath("$.data.submission.body.rating").value(2)).andExpect(jsonPath("$.data.submission.body.uploads[0]").value(newImage));
        assertThrows(BusinessException.class,()->attachments.publishedReview(oldImage));assertThrows(BusinessException.class,()->attachments.publishedReview(newImage));approve(id);assertEquals("补充后的新内容🌿",mine().get("content"));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_point_log where kind='REVIEW_REWARD'",Integer.class));
    }

    @Test void appendSupplementUpdatesOwnChildAndGetWithoutChangingOriginalRatingOrRewards()throws Exception{
        String parent=approveOriginal(),paid=str((Map<String,Object>)((Map<String,Object>)context(str(mine(),"orderId"),parent).get("submission")).get("body"),"orderId"),oldImage=upload(customer,2,"REVIEW"),newImage=upload(customer,2,"REVIEW"),child=submitAppend(parent,"content","原追评内容","uploads",Arrays.asList(oldImage));
        workflow.review(owner,child,map("decision","SUPPLEMENT","reason","请补长期使用照片"));long before=number(db.pointAccount(0,201),"available");
        Map<String,Object> changed=workflow.save(customer,2,"review_append",append(parent,"id",child,"content","新追评长期体验","rating",1,"uploads",Arrays.asList(newImage)),false);
        assertEquals(child,changed.get("id"));assertEquals("PENDING",changed.get("status"));assertEquals("APPROVED",db.one("select status from hx_document where id=?",parent).get("status"));assertEquals(Collections.emptyList(),mine().get("appends"));
        Map<String,Object> submission=(Map<String,Object>)context(paid,parent).get("appendSubmission"),body=(Map<String,Object>)submission.get("body");assertEquals("新追评长期体验",body.get("content"));assertEquals(Arrays.asList(newImage),body.get("uploads"));assertFalse(body.containsKey("rating"));assertEquals(5,number(mine(),"rating"));
        HexuIdentity identity=new HexuIdentity(db);MockMvc http=MockMvcBuilders.standaloneSetup(new HexuAppController(identity,db,null,null,null,workflow,null,null,null,null)).setControllerAdvice(new HexuErrors()).build();
        http.perform(get("/hexu/app/review-context").header("Authorization","Bearer "+identity.session(customer.id)).param("shopId","2").param("orderId",paid).param("skuId","stapler").param("parentId",parent)).andExpect(status().isOk()).andExpect(jsonPath("$.data.appendSubmission.id").value(child)).andExpect(jsonPath("$.data.appendSubmission.status").value("PENDING")).andExpect(jsonPath("$.data.appendSubmission.body.content").value("新追评长期体验")).andExpect(jsonPath("$.data.appendSubmission.body.uploads[0]").value(newImage));
        assertThrows(BusinessException.class,()->attachments.publishedReview(newImage));approve(child);assertEquals(before,number(db.pointAccount(0,201),"available"));assertEquals("新追评长期体验",((Map<?,?>)((List<?>)mine().get("appends")).get(0)).get("content"));assertThrows(BusinessException.class,()->attachments.publishedReview(oldImage));
    }

    @Test void ownContextReviewNotesArePrivateWhitelistFieldsAndNeverPublic()throws Exception{
        String paid=order(true),parent=review(paid);workflow.review(owner,parent,map("decision","SUPPLEMENT","reason","仅本人可见的首评补件意见"));
        assertEquals("仅本人可见的首评补件意见",((Map<?,?>)context(paid,null).get("submission")).get("review_note"));review(paid,"id",parent);approve(parent);String child=submitAppend(parent);workflow.review(owner,child,map("decision","SUPPLEMENT","reason","仅本人可见的追评补件意见"));
        assertEquals("仅本人可见的追评补件意见",((Map<?,?>)context(paid,parent).get("appendSubmission")).get("review_note"));
        for(Actor actor:Arrays.asList(null,other,customer)){String json=db.json(workflow.reviews(actor,2,"stapler"));assertFalse(json.contains("仅本人可见"));assertFalse(json.contains("review_note"));}
        HexuIdentity identity=new HexuIdentity(db);MockMvc http=MockMvcBuilders.standaloneSetup(new HexuAppController(identity,db,null,null,null,workflow,null,null,null,null)).setControllerAdvice(new HexuErrors()).build();
        http.perform(get("/hexu/app/review-context").header("Authorization","Bearer "+identity.session(customer.id)).param("shopId","2").param("orderId",paid).param("skuId","stapler").param("parentId",parent)).andExpect(status().isOk()).andExpect(jsonPath("$.data.appendSubmission.review_note").value("仅本人可见的追评补件意见")).andExpect(jsonPath("$.data.appendSubmission.member_id").doesNotExist()).andExpect(jsonPath("$.data.appendSubmission.body_json").doesNotExist());
        http.perform(get("/hexu/app/review-context").header("Authorization","Bearer "+identity.session(other.id)).param("shopId","2").param("orderId",paid).param("skuId","stapler").param("parentId",parent)).andExpect(status().isBadRequest());
        http.perform(get("/hexu/app/review-context").header("Authorization","Bearer "+identity.session(customer.id)).param("shopId","3").param("orderId",paid).param("skuId","stapler").param("parentId",parent)).andExpect(status().isBadRequest());
    }

    @Test void skuFilterRunsBeforePublicLimit(){
        String paid=order(true),parent=review(paid);approve(parent);db.jdbc().update("update hx_document set created_at='2000-01-01 00:00:00' where id=?",parent);
        for(int i=0;i<501;i++)legacyDocument("DOC-other-sku-"+i,"review","APPROVED",map("orderId","legacy-"+i,"skuId","cup","rating",4,"content","其他商品的真实历史导入"),"2026-01-01 00:00:00");
        assertEquals(1,workflow.reviews(2,"stapler").size());assertEquals(parent,mine().get("id"));assertEquals(500,workflow.reviews(2,"cup").size());
    }
    @Test void directedContextFindsOwnSupplementAndParentBeyondListCaps(){
        String paid=order(true),original=review(paid);workflow.review(owner,original,map("decision","SUPPLEMENT","reason","请补充实际体验"));
        db.jdbc().update("update hx_document set created_at='2000-01-01 00:00:00',updated_at='2000-01-01 00:00:00',body_json=? where id=?",db.json(original(paid,"sensitiveLegacyField","不得返回","content","需补充的本人内容")),original);
        for(int i=0;i<205;i++)legacyDocument("DOC-own-newer-"+i,"review","PENDING",original("different-order-"+i),"2026-01-01 00:00:00");
        assertFalse(workflow.list(customer,2,"review",false).stream().anyMatch(d->original.equals(d.get("id"))));
        Map<String,Object> own=context(paid,null),submission=(Map<String,Object>)own.get("submission");assertTrue(bool(own,"reviewAllowed"));assertEquals(original,submission.get("id"));assertEquals("SUPPLEMENT",submission.get("status"));assertFalse(((Map<?,?>)submission.get("body")).containsKey("sensitiveLegacyField"));
        review(paid,"id",original);approve(original);db.jdbc().update("update hx_document set created_at='2000-01-01 00:00:00' where id=?",original);
        for(int i=0;i<501;i++)legacyDocument("DOC-same-sku-newer-"+i,"review","APPROVED",original("another-order-"+i),"2026-01-01 00:00:00");
        assertFalse(workflow.reviews(customer,2,"stapler").stream().anyMatch(d->original.equals(d.get("id"))));
        String child=submitAppend(original);workflow.review(owner,child,map("decision","SUPPLEMENT","reason","补充使用期限"));
        Map<String,Object> targeted=context(paid,original),parent=(Map<String,Object>)targeted.get("parent"),appended=(Map<String,Object>)targeted.get("appendSubmission");
        assertEquals(original,parent.get("id"));assertTrue(bool(parent,"appendSupplementAllowed"));assertEquals(child,appended.get("id"));assertEquals("SUPPLEMENT",appended.get("status"));
    }
    @Test void contextRejectsMissingWrongParentPurchaseShopAndIdentity()throws Exception{
        String paid=order(true),parent=review(paid);approve(parent);String different=order(true),pending=review(different);
        HexuReviews reviews=new HexuReviews(db);
        for(String id:Arrays.asList("missing",pending))assertThrows(BusinessException.class,()->context(paid,id));
        assertThrows(BusinessException.class,()->context(different,parent));assertThrows(BusinessException.class,()->reviews.context(other,2,paid,"stapler",parent));assertThrows(BusinessException.class,()->reviews.context(customer,3,paid,"stapler",parent));assertThrows(BusinessException.class,()->reviews.context(customer,2,paid,"cup",parent));
        HexuIdentity identity=new HexuIdentity(db);MockMvc http=MockMvcBuilders.standaloneSetup(new HexuAppController(identity,db,null,null,null,workflow,null,null,null,null)).setControllerAdvice(new HexuErrors()).build();
        http.perform(get("/hexu/app/review-context").param("shopId","2").param("orderId",paid).param("skuId","stapler")).andExpect(status().isUnauthorized());
        http.perform(get("/hexu/app/review-context").header("Authorization","Bearer "+identity.session(customer.id)).param("shopId","2").param("orderId",paid).param("skuId","stapler").param("parentId",parent)).andExpect(status().isOk()).andExpect(jsonPath("$.data.shopId").value(2)).andExpect(jsonPath("$.data.parent.id").value(parent)).andExpect(jsonPath("$.data.parent.appendSupplementAllowed").value(false));
        http.perform(get("/hexu/app/review-context").header("Authorization","Bearer "+identity.session(customer.id)).param("shopId","2").param("orderId",paid).param("skuId","stapler").param("parentId","missing")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value("原评价不存在、未公开或不属于该订单商品"));
        http.perform(get("/hexu/app/review-context").header("Authorization","Bearer "+identity.session(other.id)).param("shopId","2").param("orderId",paid).param("skuId","stapler").param("parentId",parent)).andExpect(status().isBadRequest());
    }
    @Test void partialRefundDisablesNewAndSupplementAppendButKeepsPublishedHistory()throws Exception{
        String paid=order(true),image=upload(customer,2,"REVIEW"),parent=review(paid,"uploads",Arrays.asList(image));approve(parent);String child=submitAppend(parent);workflow.review(owner,child,map("decision","SUPPLEMENT","reason","补充说明"));
        assertTrue(bool(mine(),"appendSupplementAllowed"));assertFalse(bool(mine(),"appendAllowed"));
        db.jdbc().update("update hx_order set refunded=1 where id=?",paid);
        assertFalse(bool(mine(),"appendSupplementAllowed"));assertFalse(bool(mine(),"appendAllowed"));Map<String,Object> targeted=context(paid,parent);assertFalse(bool(targeted,"reviewAllowed"));assertFalse(bool((Map<String,Object>)targeted.get("parent"),"appendSupplementAllowed"));
        assertThrows(BusinessException.class,()->submitAppend(parent,"id",child));assertEquals("SUPPLEMENT",db.one("select status from hx_document where id=?",child).get("status"));assertArrayEquals(png,attachments.publishedReview(image).getBody());assertEquals(parent,mine().get("id"));
    }
    @Test void sameSecondRejectedAppendCannotShadowActiveSupplement(){
        String paid=order(true),parent=review(paid);approve(parent);Map<String,Object> body=append(parent,"orderId",paid);
        legacyDocument("DOCzzzz-rejected","review_append","REJECTED",body,"2026-01-01 00:00:00");legacyDocument("DOCaaaa-supplement","review_append","SUPPLEMENT",body,"2026-01-01 00:00:00");
        assertEquals("SUPPLEMENT",mine().get("appendStatus"));assertEquals("DOCaaaa-supplement",mine().get("appendId"));assertTrue(bool(mine(),"appendSupplementAllowed"));assertFalse(bool(mine(),"appendAllowed"));
        Map<String,Object> targeted=context(paid,parent);assertEquals("DOCaaaa-supplement",((Map<?,?>)targeted.get("appendSubmission")).get("id"));assertEquals("DOCaaaa-supplement",((Map<?,?>)targeted.get("parent")).get("appendId"));
        assertThrows(BusinessException.class,()->submitAppend(parent));assertFalse(bool(workflow.reviews(other,2,"stapler").get(0),"appendSupplementAllowed"));
    }
    @Test void orderShopIsTheCanonicalReviewAndImageScope()throws Exception{
        String paid=order(true);db.jdbc().update("update hx_order set shop_id=900 where id=?",paid);String image=upload(customer,900,"REVIEW");
        assertThrows(BusinessException.class,()->review(paid));assertThrows(BusinessException.class,()->workflow.save(customer,900,"review",original(paid,"uploads",Arrays.asList(upload(customer,2,"REVIEW"))),false));
        Map<String,Object> targeted=new HexuReviews(db).context(customer,900,paid,"stapler",null);assertEquals(900L,targeted.get("shopId"));assertTrue(bool(targeted,"reviewAllowed"));
        String id=str(workflow.save(customer,900,"review",original(paid,"uploads",Arrays.asList(image)),false),"id");workflow.review(new Actor(1,true,true),id,map("decision","APPROVED"));
        assertTrue(workflow.reviews(2,"stapler").isEmpty());assertEquals(id,workflow.reviews(customer,900,"stapler").get(0).get("id"));assertArrayEquals(png,attachments.publishedReview(image).getBody());
    }
    @Test void caseInsensitiveDatabaseMatchesCannotCreateAlternatePurchaseKeys(){
        String paid=order(true),upper=paid.toUpperCase(Locale.ROOT);
        assertEquals(paid,db.one("select id from hx_order where id=?",upper).get("id"));assertEquals("stapler",db.one("select sku_id from hx_order_line where order_id=? and sku_id=?",paid,"STAPLER").get("sku_id"));
        for(String orderAlias:Arrays.asList(upper,paid+" ")){assertThrows(BusinessException.class,()->review(orderAlias));assertThrows(BusinessException.class,()->context(orderAlias,null));}
        for(String skuAlias:Arrays.asList("STAPLER","stapler ")){assertThrows(BusinessException.class,()->review(paid,"skuId",skuAlias));assertThrows(BusinessException.class,()->new HexuReviews(db).context(customer,2,paid,skuAlias,null));}
        String parent=review(paid);approve(parent);assertTrue(bool(mine(),"appendAllowed"));assertEquals(parent,((Map<?,?>)context(paid,parent).get("parent")).get("id"));assertThrows(BusinessException.class,()->submitAppend(parent,"orderId",upper));
        assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_document where kind='review'",Integer.class));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_point_log where kind='REVIEW_REWARD'",Integer.class));
        legacyDocument("DOC-legacy-case-order","review","APPROVED",original(upper),"2026-01-01 00:00:00");assertFalse(bool(workflow.reviews(customer,2,"stapler").stream().filter(d->"DOC-legacy-case-order".equals(d.get("id"))).findFirst().get(),"appendAllowed"));
    }
}
