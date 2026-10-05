package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=HexuR75RegressionTest.Config.class)
@TestPropertySource(properties="hexu.fulfillment.require-pick=false")
class HexuR75RegressionTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:hexur75;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
  @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
  @Bean ObjectMapper mapper(){return new ObjectMapper();}
  @Bean PlatformTransactionManager tx(DataSource source){return new DataSourceTransactionManager(source);}
  @Bean HexuStore store(JdbcTemplate jdbc,ObjectMapper mapper){return new HexuStore(jdbc,mapper);}
  @Bean HexuCatalogService catalog(HexuStore db){return new HexuCatalogService(db);}
  @Bean HexuOrderService orders(HexuStore db,HexuCatalogService catalog){return new HexuOrderService(db,catalog);}
  @Bean HexuFinanceService finance(HexuStore db){return new HexuFinanceService(db);}
  @Bean HexuWorkflowService workflow(HexuStore db){return new HexuWorkflowService(db);}
 }
 @Autowired DataSource source;
 @Autowired HexuStore db;
 @Autowired HexuOrderService orders;
 @Autowired HexuFinanceService finance;
 @Autowired HexuWorkflowService workflow;
 final Actor buyer=new Actor(201,false,false),owner=new Actor(101,false,false);

 @BeforeEach void reset(){
  db.jdbc().execute("DROP ALL OBJECTS");
  ResourceDatabasePopulator seed=new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));
  seed.setSqlScriptEncoding("UTF-8");seed.execute(source);HexuTestJson.register(db.jdbc());
  for(Object[] role:new Object[][]{{701,"WAREHOUSE"},{702,"ORDER"},{703,"SUPPORT"},{704,"FINANCE"}})
   db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(?,2,?)",role);
 }
 Map<String,Object> orderBody(){return HexuOrderConsentFixture.accepted(db,2,map("items",List.of(map("id","stapler","qty",1)),"address",map("name","回归收货人","detail","回归地址")));}

 Object configuration(Actor actor,long shop){
  HexuIdentity identity=new HexuIdentity(db){@Override public Actor member(HttpServletRequest request){return actor;}};
  HexuFulfillmentController controller=new HexuFulfillmentController(identity,db,null,null,new TrackingGateway(){});
  return controller.configuration(new MockHttpServletRequest("GET","/hexu/app/management/tracking/configuration"),shop);
 }

 @Test void fulfillmentRolesReadOnlyConfigurationRespectsShopBoundary(){
  for(long user:new long[]{701,702,703}){
   Map<?,?> response=(Map<?,?>)configuration(new Actor(user,false,true),2);
   assertEquals(false,((Map<?,?>)response.get("data")).get("configured"));
  }
  assertThrows(BusinessException.class,()->configuration(new Actor(704,false,true),2));
  assertThrows(BusinessException.class,()->configuration(new Actor(701,false,true),3));
  assertThrows(BusinessException.class,()->configuration(buyer,2));
 }

 @Test void deletedCouponClaimDisappearsAndReleasesQuotaButUsedClaimRemains(){
  String campaign=str(workflow.save(owner,2,"coupon",map("name","回归优惠券","discount",100,"threshold",1000,"quantity",1,"expiresAt",System.currentTimeMillis()+86400000),true),"id");
  String deleted=str(workflow.claim(buyer,2,campaign),"id");
  db.jdbc().update("update hx_document set status='DELETED' where id=?",deleted);
  List<Map<String,Object>> available=workflow.coupons(buyer,2);
  assertFalse(available.stream().anyMatch(row->deleted.equals(row.get("id"))));
  assertEquals(1,number(available.stream().filter(row->campaign.equals(row.get("id"))).findFirst().orElseThrow(),"remainingQuantity"));
  String used=str(workflow.claim(buyer,2,campaign),"id");
  db.jdbc().update("update hx_document set status='USED' where id=?",used);
  assertTrue(workflow.coupons(buyer,2).stream().anyMatch(row->used.equals(row.get("id"))));
  assertEquals(0,number(workflow.coupons(buyer,2).stream().filter(row->campaign.equals(row.get("id"))).findFirst().orElseThrow(),"remainingQuantity"));
  assertThrows(BusinessException.class,()->workflow.claim(buyer,2,campaign));
 }

 @Test void otherMembersOrderAndMissingOrderHaveIdenticalExternalError(){
  String id=str(orders.create(buyer,2,orderBody()),"id"),missing="HX00000000000000000000000000000000";
  Actor other=new Actor(202,false,false);
  String hidden=assertThrows(BusinessException.class,()->orders.detail(other,id,false)).getMessage();
  String absent=assertThrows(BusinessException.class,()->orders.detail(other,missing,false)).getMessage();
  assertEquals(hidden,absent);
  assertEquals(hidden,assertThrows(BusinessException.class,()->orders.detail(new Actor(701,false,true),id,false)).getMessage());
  assertEquals(id,orders.detail(buyer,id,false).get("id"));
  assertEquals(id,orders.detail(owner,id,true).get("id"));
 }
 @Test void orderDetailHttpDoesNotRevealWhetherAnotherMembersIdExists()throws Exception{
  String id=str(orders.create(buyer,2,orderBody()),"id");
  String token=new HexuIdentity(db).session(202);
  MockMvc http=MockMvcBuilders.standaloneSetup(new HexuAppController(new HexuIdentity(db),db,null,orders,null,null,null,null,null,null))
      .setControllerAdvice(new HexuErrors()).build();
  for(String candidate:Arrays.asList(id,"HX00000000000000000000000000000000"))
   http.perform(get("/hexu/app/orders/{id}",candidate).header("Authorization","Bearer "+token))
       .andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value("订单不存在或无权查看"));
 }

 @Test void changedCheckoutTotalCannotCreateOrder(){
  Map<String,Object> body=orderBody();
  String campaign=str(workflow.save(owner,2,"promotion_rule",map("name","限时活动","type","LIMITED","skuIds",List.of("stapler"),"rateBps",5000,"startsAt",0,"expiresAt",System.currentTimeMillis()+60000,"quantity",10,"perMember",1),true),"id");
  long quoted=number(orders.quote(buyer,2,body),"total");
  Map<String,Object> rule=db.parse(db.one("select body_json from hx_document where id=?",campaign).get("body_json"));
  rule.put("expiresAt",System.currentTimeMillis()-1);
  db.jdbc().update("update hx_document set body_json=? where id=?",db.json(rule),campaign);
  long current=number(orders.quote(buyer,2,body),"total");
  assertTrue(current>quoted);
  body.put("expectedTotal",quoted);
  long before=db.jdbc().queryForObject("select count(*) from hx_order where buyer_id=?",Long.class,buyer.id);
  long stock=db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Long.class);
  assertEquals("订单金额已变化，请刷新报价后再次确认",assertThrows(BusinessException.class,()->orders.create(buyer,2,body)).getMessage());
  assertEquals(before,db.jdbc().queryForObject("select count(*) from hx_order where buyer_id=?",Long.class,buyer.id));
  assertEquals(stock,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Long.class));
  body.put("expectedTotal",current);
  assertEquals(current,number(orders.create(buyer,2,body),"total"));
 }

 @Test void pendingMigrationCannotBeCreatedTwice(){
  Map<String,Object> body=map("targetShopId",3,"targetAgentId",20,"reason","调整归属");
  String first=str(workflow.save(buyer,2,"migration",body,false),"id");
  assertEquals("已有进行中的迁移申请，请等待审核或补充原申请",assertThrows(BusinessException.class,()->workflow.save(buyer,2,"migration",body,false)).getMessage());
  assertEquals(1L,db.jdbc().queryForObject("select count(*) from hx_document where shop_id=2 and member_id=? and kind='migration' and status='PENDING'",Long.class,buyer.id));
  assertEquals(first,workflow.list(buyer,2,"migration",false).get(0).get("id"));
 }

 @Test void concurrentMigrationSubmissionsCreateOnlyOnePendingRecord() throws Exception {
  ExecutorService pool=Executors.newFixedThreadPool(2);
  CountDownLatch start=new CountDownLatch(1);
  Callable<Boolean> submit=()->{start.await();try{workflow.save(buyer,2,"migration",map("targetShopId",3,"targetAgentId",20,"reason","调整归属"),false);return true;}catch(BusinessException e){return false;}};
  try{
   Future<Boolean> first=pool.submit(submit),second=pool.submit(submit);start.countDown();
   assertEquals(1,(first.get(10,TimeUnit.SECONDS)?1:0)+(second.get(10,TimeUnit.SECONDS)?1:0));
   assertEquals(1L,db.jdbc().queryForObject("select count(*) from hx_document where shop_id=2 and member_id=? and kind='migration' and status='PENDING'",Long.class,buyer.id));
  }finally{pool.shutdownNow();}
 }

 @Test void wholesaleRulesRequireDestinationOwnerAndPurchaseRank(){
  Map<String,Object> rules=orders.wholesaleRules(owner,900,2);
  assertEquals(false,rules.get("mixedEnabled"));
  assertEquals(24L,rules.get("mixCapacity"));
  assertEquals(2L,rules.get("minMixBoxes"));
  assertThrows(BusinessException.class,()->orders.wholesaleRules(buyer,900,2));
  assertThrows(BusinessException.class,()->orders.wholesaleRules(owner,900,3));
 }

 @Test void completedExchangeRepeatHasAccurateErrorAndNoSecondWrite(){
  String order=str(orders.create(buyer,2,orderBody()),"id");
  Map<String,Object> payment=orders.payment(buyer,order);
  orders.paymentConfirmed(str(payment,"id"),str(payment,"merchant_no"),number(payment,"amount"),"R75-"+order);
  orders.ship(owner,order,map("carrier","顺丰","tracking","SF123456789"));orders.receive(buyer,order);
  long line=number(db.one("select id from hx_order_line where order_id=?",order),"id");
  String refund=str(finance.refundApply(buyer,order,map("lineId",line,"qty",1,"type","EXCHANGE","reason","回归换货")),"id");
  assertEquals("换货商品尚未发出",assertThrows(BusinessException.class,()->finance.exchangeReceive(buyer,refund)).getMessage());
  finance.refundReview(owner,refund,map("approve",true));
  finance.returnTracking(buyer,refund,map("carrier","顺丰","tracking","SF123456789"));
  finance.inspect(owner,refund,0);
  finance.exchangeShip(owner,refund,map("carrier","顺丰","tracking","SF987654321"));
  assertEquals("CLOSED",finance.exchangeReceive(buyer,refund).get("status"));
  Map<String,Object> before=db.one("select * from hx_refund where id=?",refund);
  long audits=db.jdbc().queryForObject("select count(*) from hx_audit where reference_id=? and action='EXCHANGE_RECEIVE'",Long.class,refund);
  long stock=db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Long.class);
  assertEquals("换货已完成，无需重复确认",assertThrows(BusinessException.class,()->finance.exchangeReceive(buyer,refund)).getMessage());
  assertEquals(before,db.one("select * from hx_refund where id=?",refund));
  assertEquals(audits,db.jdbc().queryForObject("select count(*) from hx_audit where reference_id=? and action='EXCHANGE_RECEIVE'",Long.class,refund));
  assertEquals(stock,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Long.class));
 }
}
