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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=HexuProfileTest.Config.class)
class HexuProfileTest {
    @Configuration @EnableTransactionManagement static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:hexuprofiletest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean org.springframework.transaction.PlatformTransactionManager tx(DataSource source){return new DataSourceTransactionManager(source);}
        @Bean HexuStore store(JdbcTemplate jdbc,ObjectMapper mapper){return new HexuStore(jdbc,mapper);}
        @Bean HexuProfileService profiles(HexuStore store){return new HexuProfileService(store);}
        @Bean HexuWorkflowService workflows(HexuStore store){return new HexuWorkflowService(store);}
    }
    @Autowired DataSource dataSource;
    @Autowired HexuStore db;
    @Autowired HexuProfileService profiles;
    @Autowired HexuWorkflowService workflows;
    final Actor customer=new Actor(201,false,false),other=new Actor(202,false,false),owner=new Actor(101,false,false);
    final WechatIdentityGateway phoneGateway=new WechatIdentityGateway(){
        public Map<String,Object> exchangeCode(String code){throw new UnsupportedOperationException();}
        public Map<String,Object> authorizedPhone(String code){requireCode(code);return map("phone","13812345678");}
        private void requireCode(String code){BusinessException.require("verified-phone-code".equals(code),"授权已失效");}
    };

    @BeforeEach void prepare(){
        db.jdbc().execute("DROP ALL OBJECTS");
        ResourceDatabasePopulator seed=new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));
        seed.setSqlScriptEncoding("UTF-8");seed.execute(dataSource);
    }
    Map<String,Object> details(){return map("name","新昵称🌿","gender","男","birthday","1996-02-29","region",Arrays.asList("浙江省","杭州市","余杭区"),"signature","生活有序，发现美好","avatarId","");}
    void linkWechat(long member){db.jdbc().update("insert into hx_wechat_account(app_id,open_id,member_id) values('wx1234567890abcdef',?,?)","profile-openid-"+member,member);}
    String repeat(String value,int count){return String.join("",Collections.nCopies(count,value));}
    MockHttpServletRequest request(Actor actor){MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+new HexuIdentity(db).session(actor.id));return request;}

    @Test void defaultsUseRealMemberFieldsAndNoInventedPersonalDetails(){
        Map<String,Object> profile=profiles.read(customer);
        assertEquals("林小禾",profile.get("name"));assertEquals("13888885678",profile.get("phone"));
        assertEquals("13888885678",profile.get("contactPhone"));
        for(String key:Arrays.asList("gender","birthday","signature","avatarId"))assertEquals("",profile.get(key));
        assertEquals(Collections.emptyList(),profile.get("region"));
        assertThrows(BusinessException.class,()->profiles.read(new Actor(201,true,true)));
    }
    @Test void manualContactPhonePersistsWithoutClaimingVerifiedPhone(){
        Map<String,Object> saved=profiles.save(customer,map("contactPhone"," 13900139000 "));
        assertEquals("13900139000",saved.get("contactPhone"));
        assertEquals("13888885678",saved.get("phone"));
        assertEquals("13888885678",db.one("select phone from hx_member where id=?",customer.id).get("phone"));
        assertEquals("13900139000",profiles.read(customer).get("contactPhone"));
        assertFalse(((Map<?,?>)profiles.managementRead(owner,2,customer.id).get("profile")).containsKey("contactPhone"));
        assertEquals("",profiles.save(customer,map("contactPhone","")).get("contactPhone"));
        assertEquals("13888885678",profiles.read(customer).get("phone"));
    }
    @Test void invalidManualContactPhoneDoesNotWriteProfileOrAudit(){
        int before=db.jdbc().queryForObject("select count(*) from hx_audit where actor_id=? and action='SAVE_profile'",Integer.class,customer.id);
        for(Object phone:Arrays.asList("12900139000","1390013900","139001390000","13900139a00",123))
            assertThrows(BusinessException.class,()->profiles.save(customer,map("contactPhone",phone)));
        assertEquals(before,db.jdbc().queryForObject("select count(*) from hx_audit where actor_id=? and action='SAVE_profile'",Integer.class,customer.id));
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where member_id=? and kind='profile'",Integer.class,customer.id));
    }
    @Test void allFieldsPersistAndRepeatedSavesHaveOneAccountWideRecord(){
        Map<String,Object> first=profiles.save(customer,details());
        for(Map.Entry<String,Object> field:details().entrySet())assertEquals(field.getValue(),first.get(field.getKey()));
        assertEquals(first,profiles.read(customer));
        assertEquals(first,profiles.save(customer,details()));
        assertEquals("新昵称🌿",db.one("select name from hx_member where id=201").get("name"));
        assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_document where member_id=201 and kind='profile'",Integer.class));
        assertEquals(0,number(db.one("select shop_id from hx_document where member_id=201 and kind='profile'"),"shop_id"));
        assertEquals("",profiles.read(other).get("signature"));
    }
    @Test void signatureSpacingAndLineBreaksRoundTripWithoutSilentTrimming(){
        String signature="  第一行\n第二行  ";
        assertEquals(signature,profiles.save(customer,map("signature",signature)).get("signature"));
        assertEquals(signature,profiles.read(customer).get("signature"));
        assertThrows(BusinessException.class,()->profiles.save(customer,map("signature",String.join("",Collections.nCopies(101," ")))));
    }
    @Test void oldShopProfilesReadAndMigrateWithoutDiscardingOtherRecords(){
        Map<String,Object> legacy=map("昵称","旧昵称","性别","女","生日","1995-09-23","所在地区","浙江省 杭州市 余杭区","个性签名","已保存签名","phone","13800000000");
        db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values('OLDPROFILE',2,201,'profile','ACTIVE',?)",db.json(legacy));
        assertEquals("林小禾",profiles.read(customer).get("name"));
        assertEquals("已保存签名",profiles.read(customer).get("signature"));
        assertEquals("13888885678",profiles.read(customer).get("phone"));
        Map<String,Object> saved=workflows.save(customer,3,"profile",map("kind","profile","name","跨店昵称","性别","不透露","phone","13888885678"),false);
        assertEquals(0,number(saved,"shop_id"));assertEquals("不透露",profiles.read(customer).get("gender"));
        assertEquals("已保存签名",profiles.read(customer).get("signature"));
        assertEquals("ARCHIVED",db.one("select status from hx_document where id='OLDPROFILE'").get("status"));
        assertNotNull(db.one("select id from hx_document where id='ADDR201' and status='ACTIVE'"));
        assertThrows(BusinessException.class,()->workflows.save(other,2,"profile",map("id",saved.get("id"),"name","覆盖"),false));
    }
    @Test void invalidFieldsAreRejectedBeforeAnyNameOrProfileMutation(){
        List<Map<String,Object>> invalid=Arrays.asList(
            map("name",""),map("name",repeat("🌿",21)),map("name",123),map("name","a\n"),map("name","a\u0085b"),map("gender","未知"),
            map("birthday","1995-02-29"),map("birthday","2025-04-31"),map("birthday",LocalDate.now().plusDays(2).toString()),
            map("birthday","0000-01-01"),map("birthday","1899-12-31"),map("birthday","2020-1-01"),map("signature",repeat("🌿",101)),map("signature","a\u0000b"),
            map("region","浙江省 杭州市"),map("region",Collections.singletonList("浙江省")),
            map("region",Arrays.asList("浙江省","","余杭区")),map("region",Arrays.asList("浙江省","杭州市1")),
            map("region",Arrays.asList("浙江省","abc")),map("region",Arrays.asList("浙江省","杭")),
            map("region",Arrays.asList("火星省","土星市","木星区")),map("region",Arrays.asList("浙江省","泉州市","安溪县")),
            map("region",Arrays.asList("浙江省",repeat("禾",31))),map("region",Arrays.asList("浙江省","杭州市!")),
            map("avatarId","missing"),map("signature",null),map("phone","13812345678"),map("memberId",202));
        for(Map<String,Object> value:invalid)assertThrows(BusinessException.class,()->profiles.save(customer,value),value.toString());
        assertEquals("林小禾",profiles.read(customer).get("name"));
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='profile'",Integer.class));
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_audit where action='SAVE_profile'",Integer.class));
        assertThrows(BusinessException.class,()->workflows.save(customer,2,"profile",map("name","有效昵称","phone","13812345678"),false));
        assertEquals("13888885678",profiles.read(customer).get("phone"));
    }
    @Test void oldInvalidRegionIsVisibleUntilMemberCorrectsIt(){
        List<String> old=Arrays.asList("火星省","土星市","木星区");
        db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values('OLD_BAD_REGION',2,201,'profile','ACTIVE',?)",db.json(map("region",old)));
        assertEquals(old,profiles.read(customer).get("region"));
        assertThrows(BusinessException.class,()->profiles.save(customer,map("name","新名字")));
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_audit where action='SAVE_profile'",Integer.class));
        assertEquals(Arrays.asList("福建省","泉州市","安溪县"),profiles.save(customer,map("region",Arrays.asList("福建省","泉州市","安溪县"))).get("region"));
    }
    @Test void unicodeOnlyBlankNamesRejectAndValidUnicodeTrimPreservesLimits(){
        for(String blank:Arrays.asList("\u3000","\u00a0","\u2003","\ufeff"," \u3000\u00a0\ufeff "))
            assertThrows(BusinessException.class,()->profiles.save(customer,map("name",blank)));
        for(int point=0;point<=0x9f;point++)if(Character.isISOControl(point)){
            String name="林"+Character.toString((char)point)+"禾";
            assertThrows(BusinessException.class,()->profiles.save(customer,map("name",name)));
        }
        assertThrows(BusinessException.class,()->workflows.save(customer,2,"profile",map("name","\u3000"),false));
        assertEquals("林小禾",profiles.read(customer).get("name"));
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='profile'",Integer.class));
        String name=repeat("🌿",20);
        assertEquals(name,profiles.save(customer,map("name","\u3000\ufeff"+name+"\u00a0")).get("name"));
        assertThrows(BusinessException.class,()->profiles.save(customer,map("name","\u3000"+name+"🌿\u00a0")));
    }
    @Test void unicodeLimitsAndExplicitOptionalClearsWork(){
        profiles.save(customer,map("name",repeat("🌿",20),"signature",repeat("🌿",100),"region",Arrays.asList("香港特别行政区","香港岛")));
        assertEquals(20,str(profiles.read(customer),"name").codePointCount(0,str(profiles.read(customer),"name").length()));
        Map<String,Object> cleared=profiles.save(customer,map("gender","","birthday","","region",Collections.emptyList(),"signature","","avatarId","","phone","13888885678"));
        assertEquals(Collections.emptyList(),cleared.get("region"));assertEquals("",cleared.get("signature"));
        assertEquals("1900-01-01",profiles.save(customer,map("birthday","1900-01-01")).get("birthday"));
        String today=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();assertEquals(today,profiles.save(customer,map("birthday",today)).get("birthday"));
    }
    @Test void avatarUploadsStayPrivateUntilBoundAndCannotExposeIdentityAttachments()throws Exception{
        HexuAttachmentController attachments=new HexuAttachmentController(new HexuIdentity(db),db);
        ReflectionTestUtils.setField(attachments,"secret","profile-test-secret-at-least-32-characters");
        BufferedImage image=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        MockMultipartFile file=new MockMultipartFile("file","avatar.png","image/png",bytes.toByteArray());
        Map<String,Object> uploaded=(Map<String,Object>)((Map<?,?>)attachments.upload(request(customer),2,"AVATAR",file)).get("data");
        String avatar=str(uploaded,"id");
        assertEquals(0,number(db.one("select shop_id from hx_attachment where id=?",avatar),"shop_id"));
        assertThrows(BusinessException.class,()->attachments.publishedAvatar(avatar));
        profiles.save(customer,map("avatarId",avatar));
        assertEquals(avatar,profiles.read(customer).get("avatarId"));
        assertEquals(avatar,((Map<?,?>)profiles.managementRead(owner,2,customer.id).get("profile")).get("avatarId"));
        ResponseEntity<byte[]> published=attachments.publishedAvatar(avatar);
        assertArrayEquals(bytes.toByteArray(),published.getBody());
        assertEquals("image/png",published.getHeaders().getContentType().toString());
        assertEquals("no-store",published.getHeaders().getCacheControl());assertEquals("nosniff",published.getHeaders().getFirst("X-Content-Type-Options"));
        assertThrows(BusinessException.class,()->profiles.save(other,map("avatarId",avatar)));
        Map<String,Object> identity=(Map<String,Object>)((Map<?,?>)attachments.upload(request(customer),2,"IDENTITY",file)).get("data");
        assertThrows(BusinessException.class,()->profiles.save(customer,map("avatarId",identity.get("id"))));
        assertThrows(BusinessException.class,()->attachments.publishedAvatar(str(identity,"id")));
        profiles.save(customer,map("avatarId",""));assertThrows(BusinessException.class,()->attachments.publishedAvatar(avatar));
        assertThrows(BusinessException.class,()->attachments.upload(request(customer),2,"AVATAR",new MockMultipartFile("file","fake.png","image/png",new byte[]{1,2,3})));
    }
    @Test void legacyProfileAvatarDisplaysUntilAccountWideProfileReplacesIt()throws Exception{
        HexuAttachmentController attachments=new HexuAttachmentController(new HexuIdentity(db),db);
        ReflectionTestUtils.setField(attachments,"secret","profile-test-secret-at-least-32-characters");
        BufferedImage image=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        MockMultipartFile file=new MockMultipartFile("file","avatar.png","image/png",bytes.toByteArray());
        Map<String,Object> uploaded=(Map<String,Object>)((Map<?,?>)attachments.upload(request(customer),2,"AVATAR",file)).get("data");
        String avatar=str(uploaded,"id");
        db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values('LEGACY_AVATAR',2,201,'profile','ACTIVE',?)",db.json(map("avatarId",avatar)));
        assertEquals(avatar,profiles.read(customer).get("avatarId"));
        assertArrayEquals(bytes.toByteArray(),attachments.publishedAvatar(avatar).getBody());
        profiles.save(customer,map("avatarId",""));
        assertEquals("ARCHIVED",db.one("select status from hx_document where id='LEGACY_AVATAR'").get("status"));
        assertThrows(BusinessException.class,()->attachments.publishedAvatar(avatar));
    }
    @Test void truncatedPngWithValidDimensionsCannotBeUploadedAsAvatar()throws Exception{
        BufferedImage image=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        byte[] truncated=Arrays.copyOf(bytes.toByteArray(),33); // PNG signature and complete IHDR, without pixel data.
        try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(truncated))){
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);assertTrue(readers.hasNext());
            ImageReader reader=readers.next();try{reader.setInput(input);assertEquals(3,reader.getWidth(0));assertEquals(3,reader.getHeight(0));}finally{reader.dispose();}
        }
        HexuAttachmentController attachments=new HexuAttachmentController(new HexuIdentity(db),db);
        ReflectionTestUtils.setField(attachments,"secret","profile-test-secret-at-least-32-characters");
        int before=db.jdbc().queryForObject("select count(*) from hx_attachment",Integer.class);
        MockMultipartFile file=new MockMultipartFile("file","truncated.png","image/png",truncated);
        BusinessException error=assertThrows(BusinessException.class,()->attachments.upload(request(customer),0,"AVATAR",file));
        assertEquals("无法读取完整图片",error.getMessage());
        assertEquals(before,db.jdbc().queryForObject("select count(*) from hx_attachment",Integer.class));
    }
    @Test void returnProofsAllowWarehouseAndSupportWithoutOpeningIdentityAccess()throws Exception{
        db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(700,2,'WAREHOUSE'),(701,2,'SUPPORT'),(702,2,'FINANCE')");
        BufferedImage image=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        MockMultipartFile file=new MockMultipartFile("file","proof.png","image/png",bytes.toByteArray());
        HexuAttachmentController memberUploads=new HexuAttachmentController(new HexuIdentity(db),db);ReflectionTestUtils.setField(memberUploads,"secret","profile-test-secret-at-least-32-characters");
        Map<String,Object> sensitive=(Map<String,Object>)((Map<?,?>)memberUploads.upload(request(customer),2,"IDENTITY",file)).get("data");
        for(long staffId:new long[]{700,701,702}){
            Actor staff=new Actor(staffId,false,true);HexuIdentity staffIdentity=new HexuIdentity(db){@Override public Actor admin(){return staff;}};
            HexuAttachmentController attachments=new HexuAttachmentController(staffIdentity,db);ReflectionTestUtils.setField(attachments,"secret","profile-test-secret-at-least-32-characters");
            MockHttpServletRequest request=new MockHttpServletRequest();
            if(staffId==702){assertThrows(BusinessException.class,()->attachments.upload(request,2,"RETURN",file));continue;}
            Map<String,Object> uploaded=(Map<String,Object>)((Map<?,?>)attachments.upload(request,2,"RETURN",file)).get("data");
            assertArrayEquals(bytes.toByteArray(),attachments.read(request,str(uploaded,"id")).getBody());
            assertThrows(BusinessException.class,()->attachments.upload(request,3,"RETURN",file));
            assertThrows(BusinessException.class,()->attachments.upload(request,2,"IDENTITY",file));
            assertThrows(BusinessException.class,()->attachments.read(request,str(sensitive,"id")));
        }
    }
    @Test void phoneChangesUseOnlyVerifiedCodesAndRejectAnotherAccountsNumber(){
        assertThrows(BusinessException.class,()->profiles.authorizePhone(customer,"verified-phone-code",phoneGateway));
        linkWechat(customer.id);
        assertThrows(BusinessException.class,()->profiles.authorizePhone(customer,"invalid-code",phoneGateway));
        assertThrows(BusinessException.class,()->profiles.authorizePhone(customer,"",phoneGateway));
        Map<String,Object> result=profiles.authorizePhone(customer,"verified-phone-code",phoneGateway);
        assertEquals("13812345678",result.get("phone"));assertEquals(result,profiles.authorizePhone(customer,"verified-phone-code",phoneGateway));
        linkWechat(other.id);
        assertThrows(BusinessException.class,()->profiles.authorizePhone(other,"verified-phone-code",phoneGateway));
        assertEquals("13800000202",profiles.read(other).get("phone"));
        assertThrows(BusinessException.class,()->profiles.authorizePhone(customer,"verified-phone-code",new UnconfiguredWechatIdentityGateway()));
    }
    @Test void concurrentPhoneClaimsCannotAssignOnePhoneToTwoAccounts()throws Exception{
        linkWechat(customer.id);linkWechat(other.id);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(Actor actor:Arrays.asList(customer,other))attempts.add(pool.submit(()->{start.await();try{profiles.authorizePhone(actor,"verified-phone-code",phoneGateway);return true;}catch(BusinessException e){return false;}}));
            start.countDown();int successful=0;for(Future<Boolean> attempt:attempts)if(attempt.get(10,TimeUnit.SECONDS))successful++;
            assertEquals(1,successful);assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_member where phone='13812345678'",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void adminReadMasksPhoneAndLimitsMemberVisibilityToManagedShop(){
        profiles.save(customer,details());
        Map<String,Object> result=profiles.managementRead(owner,2,customer.id);
        assertEquals("138****5678",((Map<?,?>)result.get("member")).get("phone"));
        Map<?,?> profile=(Map<?,?>)result.get("profile");assertEquals("1996-02-29",profile.get("birthday"));
        for(String secret:Arrays.asList("phone","body_json","uploads","member_id","_secure_name"))assertFalse(profile.containsKey(secret));
        assertThrows(BusinessException.class,()->profiles.managementRead(owner,3,customer.id));
        assertThrows(BusinessException.class,()->profiles.managementRead(owner,2,401));
        db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(88,2,'WAREHOUSE')");
        assertThrows(BusinessException.class,()->profiles.managementRead(new Actor(88,false,true),2,customer.id));
        db.jdbc().update("update hx_staff set role_code='OPERATOR' where user_id=88");
        assertEquals(result,profiles.managementRead(new Actor(88,false,true),2,customer.id));
    }
    @Test void endpointsRequireMemberSessionAndReturnSavedProfileContract()throws Exception{
        MockMvc http=MockMvcBuilders.standaloneSetup(new HexuProfileController(new HexuIdentity(db),profiles,phoneGateway)).setControllerAdvice(new HexuErrors()).build();
        String token=new HexuIdentity(db).session(customer.id);
        http.perform(get("/hexu/app/profile")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        for(List<String> region:Arrays.asList(Arrays.asList("火星省","土星市","木星区"),Arrays.asList("浙江省","泉州市","安溪县")))
            http.perform(post("/hexu/app/profile").header("Authorization","Bearer "+token).contentType("application/json").content(db.json(map("region",region))))
                .andExpect(status().isBadRequest());
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='profile'",Integer.class));
        assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_audit where action='SAVE_profile'",Integer.class));
        http.perform(post("/hexu/app/profile").header("Authorization","Bearer "+token).contentType("application/json").content(db.json(details())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.name").value("新昵称🌿")).andExpect(jsonPath("$.data.region[2]").value("余杭区"));
        http.perform(get("/hexu/app/profile").header("Authorization","Bearer "+token)).andExpect(jsonPath("$.data.birthday").value("1996-02-29"));
        http.perform(post("/hexu/app/profile/phone").header("Authorization","Bearer "+token).contentType("application/json").content("{\"phone\":\"13812345678\"}"))
            .andExpect(status().isBadRequest());
        linkWechat(customer.id);
        http.perform(post("/hexu/app/profile/phone").header("Authorization","Bearer "+token).contentType("application/json").content("{\"code\":\"verified-phone-code\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("13812345678"));
        db.jdbc().update("delete from hx_session where member_id=?",customer.id);
        http.perform(get("/hexu/app/profile").header("Authorization","Bearer "+token))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
    }
}
