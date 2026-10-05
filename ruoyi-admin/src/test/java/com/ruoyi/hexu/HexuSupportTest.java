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
import org.springframework.mock.web.*;
import org.springframework.test.context.ContextConfiguration;
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
import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=HexuSupportTest.Config.class)
class HexuSupportTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:hexusupporttest;MODE=MySQL;IGNORECASE=TRUE;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean org.springframework.transaction.PlatformTransactionManager tx(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean HexuStore db(JdbcTemplate jdbc,ObjectMapper mapper){return new HexuStore(jdbc,mapper);}
        @Bean HexuCatalogService catalog(HexuStore db){return new HexuCatalogService(db);}
        @Bean HexuOrderService orders(HexuStore db,HexuCatalogService catalog){return new HexuOrderService(db,catalog);}
        @Bean HexuWorkflowService workflow(HexuStore db){return new HexuWorkflowService(db);}
        @Bean HexuCommands commands(HexuStore db){return new HexuCommands(db);}
    }
    @Autowired DataSource source;@Autowired HexuStore db;@Autowired HexuWorkflowService workflow;@Autowired HexuOrderService orders;@Autowired HexuCommands commands;
    final Actor customer=new Actor(201,false,false),other=new Actor(202,false,false),owner=new Actor(101,false,false);
    HexuAttachmentController attachments;byte[] png;
    @BeforeEach void prepare()throws Exception{
        db.jdbc().execute("DROP ALL OBJECTS");ResourceDatabasePopulator seed=new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));seed.setSqlScriptEncoding("UTF-8");seed.execute(source);
        attachments=new HexuAttachmentController(new HexuIdentity(db),db);ReflectionTestUtils.setField(attachments,"secret","support-test-encryption-at-least-32-characters");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);png=bytes.toByteArray();
    }
    Map<String,Object> input(Object... extra){Map<String,Object> body=map("question","订单问题","message","请核对本人订单的配送安排","uploads",Collections.emptyList());body.putAll(map(extra));return body;}
    Map<String,Object> save(Object... extra){return workflow.save(customer,2,"support",input(extra),false);}
    Map<String,Object> body(Map<String,Object> document){return db.parse(document.get("body_json"));}
    String order(Actor actor){return str(orders.create(actor,2,HexuOrderConsentFixture.accepted(db,2,map("items",Collections.singletonList(map("id","stapler","qty",1)),"address",map("name","实际收货人","detail","实际收货地址")))),"id");}
    String repeat(String text,int count){return String.join("",Collections.nCopies(count,text));}
    void empty(){assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='support'",Integer.class));}
    MockHttpServletRequest request(Actor actor){MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+new HexuIdentity(db).session(actor.id));return request;}
    String upload(Actor actor,long shop,String purpose)throws Exception{return str((Map<String,Object>)((Map<?,?>)attachments.upload(request(actor),shop,purpose,new MockMultipartFile("file","support.png","image/png",png))).get("data"),"id");}

    @Test void allSixRealQuestionOptionsSaveWithTrimmedUnicodeAndOnlyWhitelistedFields(){
        for(String question:Arrays.asList("订单问题","商品咨询","售后服务","账户与代理","活动咨询","其他问题")){
            Map<String,Object> saved=save("question",question,"message"," \u3000\u00a0真实留言🌿\u3000 ","shopId",999,"kind","promotion","rank",3,"phone","13800138000","name","非必要身份","_privateDraft","隐藏值");
            Map<String,Object> body=body(saved);assertEquals(new HashSet<>(Arrays.asList("question","message","orderId","uploads")),body.keySet());assertEquals(question,body.get("question"));assertEquals("真实留言🌿",body.get("message"));assertEquals(2,number(saved,"shop_id"));assertEquals("support",saved.get("kind"));assertEquals("PENDING",saved.get("status"));
        }
        assertEquals(6,workflow.list(customer,2,"support",false).size());
    }
    @Test void omittedAndEmptyAssociatedOrderRemainOptional(){
        for(Map<String,Object> input:Arrays.asList(input(),input("orderId",""),input("关联订单"," "),input("orderId","","关联订单","")))assertEquals("",body(workflow.save(customer,2,"support",input,false)).get("orderId"));
    }
    @Test void oneAndFiveHundredUnicodeCodePointsPersistAndReadBack(){
        for(String message:Arrays.asList("字",repeat("🌿",500))){Map<String,Object> saved=save("message",message);Map<String,Object> read=workflow.list(customer,2,"support",false).stream().filter(d->saved.get("id").equals(d.get("id"))).findFirst().orElseThrow(AssertionError::new);assertEquals(message,((Map<?,?>)read.get("body")).get("message"));}
    }
    @Test void emptyAndUnicodeWhitespaceMessagesReject(){for(String value:Arrays.asList(""," ","\u3000\u00a0"))assertThrows(BusinessException.class,()->save("message",value));empty();}
    @Test void feffTrimsLikeJavascriptForMessageQuestionAndOptionalOrder(){
        for(String value:Arrays.asList("\ufeff","\u3000\ufeff\u00a0"," \ufeff\n\t "))assertThrows(BusinessException.class,()->save("message",value));empty();
        String own=order(customer),message="多行\n真实\t留言🌿";
        Map<String,Object> saved=save("question","\ufeff订单问题\ufeff","message","\ufeff\u3000"+message+"\u00a0\ufeff","orderId","\ufeff"+own+"\ufeff");
        Map<String,Object> read=(Map<String,Object>)workflow.list(customer,2,"support",false).get(0).get("body");
        assertEquals("订单问题",read.get("question"));assertEquals(message,read.get("message"));assertEquals(own,read.get("orderId"));assertEquals(read,body(saved));
        assertEquals("",body(save("orderId","\ufeff","关联订单","\ufeff\u3000")).get("orderId"));
    }
    @Test void fiveHundredAndOneUnicodeCodePointsReject(){assertThrows(BusinessException.class,()->save("message",repeat("🌿",501)));empty();}
    @Test void nonStringAndMissingMessagesReject(){
        for(Object value:Arrays.asList(null,123,false,Collections.singletonList("留言"),map("text","留言")))assertThrows(BusinessException.class,()->save("message",value));
        Map<String,Object> missing=input();missing.remove("message");assertThrows(BusinessException.class,()->workflow.save(customer,2,"support",missing,false));empty();
    }
    @Test void controlsRejectEvenAtTrimEdges(){for(String value:Arrays.asList("\u0000留言","留言\u0000","留\u0001言","留\u000b言","留\u000c言","留\u007f言","留\u0085言","留\u009f言"))assertThrows(BusinessException.class,()->save("message",value));empty();}
    @Test void textareaNewlinesCrLfAndTabsPersistThroughRealSaveAndList(){
        String message="第一行\r\n第二行\t🌿\n第三行";Map<String,Object> saved=save("message"," \u3000"+message+"\u00a0 ");
        assertEquals(message,body(saved).get("message"));Map<String,Object> read=workflow.list(customer,2,"support",false).stream().filter(d->saved.get("id").equals(d.get("id"))).findFirst().orElseThrow(AssertionError::new);assertEquals(message,((Map<?,?>)read.get("body")).get("message"));
        for(String whitespace:Arrays.asList("\n","\r\n","\t","\r\n\t "))assertThrows(BusinessException.class,()->save("message",whitespace));
    }
    @Test void multilineUnicodeCountIncludesAllowedWhitespaceAndKeepsFiveHundredLimit(){
        String message="首"+repeat("🌿",495)+"\r\n\t尾";assertEquals(500,message.codePointCount(0,message.length()));Map<String,Object> saved=save("message",message);
        assertEquals(message,body(saved).get("message"));assertEquals(message,((Map<?,?>)workflow.list(customer,2,"support",false).get(0).get("body")).get("message"));assertThrows(BusinessException.class,()->save("message",message+"🌿"));
    }
    @Test void unknownNonStringAndMissingQuestionReject(){
        for(Object value:Arrays.asList(null,123,false,"", "自定义问题", "订单问题\u0000"))assertThrows(BusinessException.class,()->save("question",value));
        Map<String,Object> missing=input();missing.remove("question");assertThrows(BusinessException.class,()->workflow.save(customer,2,"support",missing,false));empty();
    }
    @Test void canonicalOrderIsValidatedAndStoredWithoutOrderPrivateDetails(){
        String order=order(customer);Map<String,Object> saved=save("orderId",order);assertEquals(order,body(saved).get("orderId"));assertFalse(body(saved).containsKey("address"));assertFalse(body(saved).containsKey("buyer_id"));assertFalse(body(saved).containsKey("total"));
    }
    @Test void legacyOrderKeyIsAcceptedAndNormalizedWithoutDuplicateBodyKey(){
        String order=order(customer);Map<String,Object> saved=save("关联订单"," "+order+" ");assertEquals(order,body(saved).get("orderId"));assertFalse(body(saved).containsKey("关联订单"));
    }
    @Test void EqualDualOrderKeysAreAcceptedButConflictingKeysReject(){
        String order=order(customer);assertEquals(order,body(save("orderId",order,"关联订单"," "+order+" ")).get("orderId"));
        for(Map<String,Object> input:Arrays.asList(input("orderId",order,"关联订单",""),input("orderId","","关联订单",order),input("orderId",order,"关联订单","fake-order")))assertThrows(BusinessException.class,()->workflow.save(customer,2,"support",input,false));
        assertEquals(1,workflow.list(customer,2,"support",false).size());
    }
    @Test void OtherMembersAndOtherShopsOrdersRejectWithSameNonDisclosingError(){
        String own=order(customer),foreign=order(other);
        BusinessException identity=assertThrows(BusinessException.class,()->save("orderId",foreign));BusinessException shop=assertThrows(BusinessException.class,()->workflow.save(customer,3,"support",input("orderId",own),false));BusinessException missing=assertThrows(BusinessException.class,()->save("orderId","missing"));
        assertEquals("本人本商城订单不存在",identity.getMessage());assertEquals(identity.getMessage(),shop.getMessage());assertEquals(identity.getMessage(),missing.getMessage());empty();
    }
    @Test void OrderKeyTypeControlsAndCaseInsensitiveAliasesReject(){
        String own=order(customer);
        for(Object value:Arrays.asList(null,123,false,Collections.emptyList(),map("id",own),own+"\n"))for(String key:Arrays.asList("orderId","关联订单"))assertThrows(BusinessException.class,()->save(key,value));
        assertEquals(own,db.one("select id from hx_order where id=?",own.toUpperCase(Locale.ROOT)).get("id"));assertThrows(BusinessException.class,()->save("orderId",own.toUpperCase(Locale.ROOT)));empty();
    }
    @Test void OwnPrivateUploadPersistsAndOnlySameShopSupportCanRead()throws Exception{
        String image=upload(customer,2,"SUPPORT"),id=str(save("uploads",Arrays.asList(image)),"id");assertEquals(Arrays.asList(image),((Map<?,?>)workflow.list(customer,2,"support",false).get(0).get("body")).get("uploads"));
        assertArrayEquals(png,attachments.read(request(customer),image).getBody());assertThrows(BusinessException.class,()->attachments.read(request(other),image));assertThrows(BusinessException.class,()->attachments.publishedReview(image));assertThrows(BusinessException.class,()->attachments.publishedProduct(image));
        db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(750,2,'SUPPORT'),(751,2,'WAREHOUSE'),(752,3,'SUPPORT')");
        assertArrayEquals(png,staffAttachments(new Actor(750,false,true)).read(new MockHttpServletRequest(),image).getBody());
        assertThrows(BusinessException.class,()->staffAttachments(new Actor(751,false,true)).read(new MockHttpServletRequest(),image));
        assertThrows(BusinessException.class,()->staffAttachments(new Actor(752,false,true)).read(new MockHttpServletRequest(),image));
        workflow.review(owner,id,map("decision","APPROVED"));assertThrows(BusinessException.class,()->attachments.publishedReview(image));assertTrue(workflow.list(other,2,"support",false).isEmpty());
    }
    @Test void ExistingGenericUploadOwnershipAndShopGuardsRemainEnforced()throws Exception{
        for(String image:Arrays.asList(upload(other,2,"SUPPORT"),upload(customer,3,"SUPPORT"),upload(customer,2,"IDENTITY"),"FILEmissing"))assertThrows(BusinessException.class,()->save("uploads",Arrays.asList(image)));empty();
    }
    @Test void UploadListTypeDuplicatesAndNineFileLimitRemainEnforced()throws Exception{
        String image=upload(customer,2,"SUPPORT");for(Object value:Arrays.asList(null,image,Arrays.asList(image,image),Arrays.asList(123),Arrays.asList(""),Collections.nCopies(10,image)))assertThrows(BusinessException.class,()->save("uploads",value));empty();
    }
    HexuAttachmentController staffAttachments(Actor staff){
        HexuIdentity identity=new HexuIdentity(db){@Override public Actor admin(){return staff;}};
        HexuAttachmentController controller=new HexuAttachmentController(identity,db);
        ReflectionTestUtils.setField(controller,"secret","support-test-encryption-at-least-32-characters");
        return controller;
    }
    @Test void LegacyIdentityImageOnlyBecomesReadableWhenExactlyLinkedToSameShopSupportDocument()throws Exception{
        String linked=upload(customer,2,"IDENTITY"),unlinked=upload(customer,2,"IDENTITY"),foreign=upload(customer,3,"IDENTITY");
        db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(750,2,'SUPPORT'),(752,3,'SUPPORT')");
        HexuAttachmentController support=staffAttachments(new Actor(750,false,true));
        assertThrows(BusinessException.class,()->support.read(new MockHttpServletRequest(),linked));
        String body=db.json(map("question","售后服务","message","旧客服工单","uploads",Arrays.asList(linked)));
        db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values('DOClegacy-upload',2,201,'support','APPROVED',?)",body);
        assertArrayEquals(png,support.read(new MockHttpServletRequest(),linked).getBody());
        assertThrows(BusinessException.class,()->support.read(new MockHttpServletRequest(),unlinked));
        assertThrows(BusinessException.class,()->support.read(new MockHttpServletRequest(),foreign));
        assertThrows(BusinessException.class,()->staffAttachments(new Actor(752,false,true)).read(new MockHttpServletRequest(),linked));
        db.jdbc().update("update hx_document set status='DELETED' where id='DOClegacy-upload'");
        assertThrows(BusinessException.class,()->support.read(new MockHttpServletRequest(),linked));
    }
    @Test void ListAndHttpReadBackUseMemberShopIsolationAndManagementRoles()throws Exception{
        String order=order(customer),id=str(save("关联订单",order),"id");assertTrue(workflow.list(other,2,"support",false).isEmpty());assertTrue(workflow.list(customer,3,"support",false).isEmpty());
        db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(750,2,'SUPPORT'),(751,2,'WAREHOUSE'),(752,3,'SUPPORT')");
        assertEquals(id,workflow.list(new Actor(750,false,true),2,"support",true).get(0).get("id"));assertThrows(BusinessException.class,()->workflow.list(new Actor(751,false,true),2,"support",true));assertThrows(BusinessException.class,()->workflow.list(new Actor(752,false,true),2,"support",true));
        HexuIdentity identity=new HexuIdentity(db);MockMvc http=MockMvcBuilders.standaloneSetup(new HexuAppController(identity,db,null,null,null,workflow,null,null,null,null)).setControllerAdvice(new HexuErrors()).build();
        http.perform(get("/hexu/app/documents/support").header("Authorization","Bearer "+identity.session(customer.id)).param("shopId","2")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].body.orderId").value(order)).andExpect(jsonPath("$.data[0].body.message").value("请核对本人订单的配送安排")).andExpect(jsonPath("$.data[0].body.kind").doesNotExist()).andExpect(jsonPath("$.data[0].body.rank").doesNotExist());
        http.perform(get("/hexu/app/documents/support").header("Authorization","Bearer "+identity.session(other.id)).param("shopId","2")).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }
    @Test void LegacyDocumentsRemainUnchangedOnReadAndNewSave(){
        Map<String,Object> legacy=map("question","其他问题","message","旧留言","关联订单","旧字段值","rank",3);String json=db.json(legacy);db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values('DOClegacy-support',2,201,'support','PENDING',?)",json);
        workflow.list(customer,2,"support",false);save();assertEquals(json,db.one("select body_json from hx_document where id='DOClegacy-support'").get("body_json"));
    }
    @Test void OwnSupplementUpdatesSameDocumentButCannotUpdateAnotherMembersSupport(){
        String id=str(save(),"id");workflow.review(owner,id,map("decision","SUPPLEMENT","reason","请补充说明"));Map<String,Object> changed=save("id",id,"message","补充后的本人留言","rank",3);assertEquals(id,changed.get("id"));assertEquals("PENDING",changed.get("status"));assertEquals("补充后的本人留言",body(changed).get("message"));assertFalse(body(changed).containsKey("id"));
        assertThrows(BusinessException.class,()->workflow.save(other,2,"support",input("id",id),false));assertEquals(1,workflow.list(customer,2,"support",false).size());
    }
    @Test void supportDecisionsUseSystemAndStaySeparateFromSaveReceiptWithoutPrivateDetails(){
        for(String decision:Arrays.asList("APPROVED","REJECTED","SUPPLEMENT")){
            String content="不在处理通知中复制的本人留言",reason="不在处理通知中复制的处理原因",id=str(save("message",content),"id");workflow.review(owner,id,map("decision",decision,"reason",reason));
            List<Map<String,Object>> messages=db.jdbc().queryForList("select * from hx_message where reference_id=? order by id",id);assertEquals(2,messages.size());assertEquals("客服留言已收到",messages.get(0).get("title"));
            Map<String,Object> reviewed=messages.get(1);assertEquals(HexuWorkflowService.reviewNotificationTitle("support",decision),reviewed.get("title"));assertEquals(reviewed.get("title"),reviewed.get("body_text"));assertEquals("SYSTEM",reviewed.get("event_type"));assertEquals(customer.id,number(reviewed,"member_id"));assertEquals(2,number(reviewed,"shop_id"));assertFalse(db.json(messages).contains(content));assertFalse(db.json(messages).contains(reason));
        }
    }
    @Test void successfulSupportSaveCreatesOnePrivateSystemReceiptForActualShopAndDocument(){
        String own=order(customer),privateMessage="本人私密留言第一行\n第二行真实说明",id=str(save("message",privateMessage,"orderId",own),"id");
        List<Map<String,Object>> receipts=db.jdbc().queryForList("select * from hx_message where reference_id=?",id);assertEquals(1,receipts.size());Map<String,Object> receipt=receipts.get(0);
        assertEquals(2,number(receipt,"shop_id"));assertEquals(customer.id,number(receipt,"member_id"));assertEquals("SYSTEM",receipt.get("event_type"));assertEquals("客服留言已收到",receipt.get("title"));assertEquals("客服留言已收到",receipt.get("body_text"));assertFalse(bool(receipt,"is_read"));
        assertFalse(db.json(receipt).contains(privateMessage));assertFalse(db.json(receipt).contains(own));
        assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_message where member_id=? and shop_id in (0,?) and reference_id=?",Integer.class,customer.id,2,id));
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_message where member_id=? and shop_id in (0,?) and reference_id=?",Integer.class,other.id,2,id));
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_message where member_id=? and shop_id in (0,?) and reference_id=?",Integer.class,customer.id,3,id));
        assertThrows(BusinessException.class,()->new HexuNotifications(db).read(other,number(receipt,"id")));assertTrue(bool(new HexuNotifications(db).read(customer,number(receipt,"id")),"is_read"));
    }
    @Test void supportReceiptUsesExistingSystemTemplateWithoutInterpolatingPrivateContent(){
        workflow.save(owner,2,"notification_template",map("event","SYSTEM","title","{{shop}} · {{message}}","content","已登记 {{reference}}：{{message}}"),true);
        String message="不得出现在消息标题或正文的真实私密全文",id=str(save("message",message),"id");Map<String,Object> receipt=db.one("select * from hx_message where reference_id=?",id);
        assertEquals("SYSTEM",receipt.get("event_type"));assertEquals("安溪禾序商城 · 客服留言已收到",receipt.get("title"));assertEquals("已登记 "+id+"：客服留言已收到",receipt.get("body_text"));assertFalse(db.json(receipt).contains(message));
    }
    @Test void idempotentReplayAndSameIdSupplementEachGenerateOnlyOneSaveReceipt(){
        Map<String,Object> original=input("shopId",2,"kind","support");int[] invoked={0};
        java.util.function.Supplier<Object> action=()->{invoked[0]++;return workflow.save(customer,2,"support",original,false);};
        Map<String,Object> saved=(Map<String,Object>)commands.execute(customer,"support-receipt-001","document-save",original,action);String id=str(saved,"id");
        assertEquals(id,str((Map<String,Object>)commands.execute(customer,"support-receipt-001","document-save",original,action),"id"));assertEquals(1,invoked[0]);
        assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_message where reference_id=? and title='客服留言已收到'",Integer.class,id));
        workflow.review(owner,id,map("decision","SUPPLEMENT","reason","请补充情况"));Map<String,Object> supplement=input("id",id,"message","补充后的真实留言","shopId",2,"kind","support");
        java.util.function.Supplier<Object> update=()->workflow.save(customer,2,"support",supplement,false);
        assertEquals(id,str((Map<String,Object>)commands.execute(customer,"support-receipt-002","document-save",supplement,update),"id"));commands.execute(customer,"support-receipt-002","document-save",supplement,update);
        assertEquals(2,db.jdbc().queryForObject("select count(*) from hx_message where reference_id=? and title='客服留言已收到'",Integer.class,id));
        assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_document where id=?",Integer.class,id));assertEquals("PENDING",db.one("select status from hx_document where id=?",id).get("status"));
    }
    @Test void failedSupportInputAndOuterTransactionDoNotLeaveReceiptDocumentOrIdempotencyRow(){
        long messages=db.jdbc().queryForObject("select count(*) from hx_message",Long.class),audits=db.jdbc().queryForObject("select count(*) from hx_audit",Long.class);
        Map<String,Object> invalid=input("orderId","missing-order","shopId",2,"kind","support");
        assertThrows(BusinessException.class,()->commands.execute(customer,"support-failed-001","document-save",invalid,()->workflow.save(customer,2,"support",invalid,false)));
        Map<String,Object> valid=input("shopId",2,"kind","support");
        assertThrows(BusinessException.class,()->commands.execute(customer,"support-rollback-001","document-save",valid,()->{workflow.save(customer,2,"support",valid,false);throw new BusinessException("测试事务失败");}));
        empty();assertEquals(messages,db.jdbc().queryForObject("select count(*) from hx_message",Long.class));assertEquals(audits,db.jdbc().queryForObject("select count(*) from hx_audit",Long.class));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_idempotency",Integer.class));
    }
    @Test void MissingAndInactiveShopAndStaffIdentityCannotCreateMemberSupport(){
        assertThrows(BusinessException.class,()->workflow.save(customer,999999,"support",input(),false));db.jdbc().update("update hx_shop set status='INACTIVE' where id=2");assertThrows(BusinessException.class,()->save());assertThrows(BusinessException.class,()->workflow.save(new Actor(1,true,true),2,"support",input(),false));empty();
    }
}
