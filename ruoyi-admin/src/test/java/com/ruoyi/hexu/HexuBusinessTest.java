package com.ruoyi.hexu;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.util.*;
import java.sql.Timestamp;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.hexu.HexuStore.*;
@ExtendWith(SpringExtension.class) @ContextConfiguration(classes=HexuBusinessTest.Config.class)
@TestPropertySource(properties="hexu.fulfillment.require-pick=false")
class HexuBusinessTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:hexutest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
  @Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}
  @Bean ObjectMapper json(){return new ObjectMapper();}
  @Bean org.springframework.transaction.PlatformTransactionManager tx(DataSource ds){return new DataSourceTransactionManager(ds);}
  @Bean HexuStore db(JdbcTemplate j,ObjectMapper m){return new HexuStore(j,m);}
  @Bean HexuCatalogService catalog(HexuStore d){return new HexuCatalogService(d);}
  @Bean HexuOrderService orders(HexuStore d,HexuCatalogService c){return new HexuOrderService(d,c);}
  @Bean HexuFinanceService finance(HexuStore d){return new HexuFinanceService(d);}
  @Bean HexuWorkflowService workflow(HexuStore d){return new HexuWorkflowService(d);}
  @Bean HexuCommands commands(HexuStore d){return new HexuCommands(d);}
  @Bean HexuAgencyService agency(HexuStore d){return new HexuAgencyService(d);}
  @Bean HexuAccountService accounts(HexuStore d){return new HexuAccountService(d);}
  @Bean HexuChannelInbox inbox(HexuStore d,HexuOrderService o,HexuFinanceService f){return new HexuChannelInbox(d,o,f);}
  @Bean HexuPolicies policies(HexuStore d){return new HexuPolicies(d);}
  @Bean HexuIdentity identity(HexuStore d){return new HexuIdentity(d);}
  @Bean WechatIdentityGateway wechatIdentity(){return new WechatIdentityGateway(){
   public Map<String,Object> exchangeCode(String code){return map("appId","wx1234567890abcdef","openId","test-login-openid");}
   public Map<String,Object> authorizedPhone(String code){return map("phone","13812345678");}
  };}
  @Bean HexuWechatController wechat(WechatIdentityGateway gateway,HexuIdentity identity,HexuStore d,HexuCatalogService c,HexuOrderService o,HexuPolicies p){return new HexuWechatController(gateway,new UnconfiguredPaymentGateway(),identity,d,c,o,p);}
 }
 @Autowired DataSource ds;@Autowired HexuStore db;@Autowired HexuCatalogService catalog;@Autowired HexuOrderService orders;@Autowired HexuFinanceService finance;@Autowired HexuWorkflowService workflow;@Autowired HexuCommands commands;
 @Autowired HexuAgencyService agency; @Autowired HexuAccountService accounts; @Autowired HexuChannelInbox inbox;
 @Autowired HexuWechatController wechat;
 @Autowired HexuIdentity identity;
 final Actor customer=new Actor(201,false,false),owner=new Actor(101,false,false),platform=new Actor(1,true,true),c1=new Actor(104,false,false);
 @BeforeEach void reset(){db.jdbc().execute("DROP ALL OBJECTS");ResourceDatabasePopulator seed=new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));seed.setSqlScriptEncoding("UTF-8");seed.execute(ds);HexuTestJson.register(db.jdbc());}
 Map<String,Object> body(String sku,int qty){return HexuOrderConsentFixture.accepted(db,2,map("items",Collections.singletonList(map("id",sku,"qty",qty)),"address",map("name","测试收货人","detail","测试地址")));}
 String create(int qty){return str(orders.create(customer,2,body("stapler",qty)),"id");}
 void pay(String id){Map<String,Object>p=orders.payment(customer,id);orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),number(p,"amount"),"CHANNEL-"+id);}
 @Test void onlineDemoPaymentIsBoundToTheBuyerAndAuditedOnce(){
  String order=create(1);Map<String,Object>payment=orders.payment(customer,order);
  HexuDemoController demo=new HexuDemoController(identity,db,orders);
  org.springframework.mock.web.MockHttpServletRequest other=new org.springframework.mock.web.MockHttpServletRequest();
  other.addHeader("Authorization","Bearer "+identity.session(owner.id));
  assertThrows(BusinessException.class,()->demo.confirmPayment(other,str(payment,"id")));
  assertEquals("UNPAID",db.one("select status from hx_order where id=?",order).get("status"));
  org.springframework.mock.web.MockHttpServletRequest buyer=new org.springframework.mock.web.MockHttpServletRequest();
  buyer.addHeader("Authorization","Bearer "+identity.session(customer.id));
  demo.confirmPayment(buyer,str(payment,"id"));demo.confirmPayment(buyer,str(payment,"id"));
  assertEquals("PAID",db.one("select status from hx_order where id=?",order).get("status"));
  assertEquals("DEMO-"+payment.get("id"),db.one("select channel_ref from hx_payment where id=?",payment.get("id")).get("channel_ref"));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='DEMO_PAYMENT' and reference_id=?",Integer.class,payment.get("id")));
 }
 @Test void onlineDemoRefundCannotRefundAnExistingNonDemoPayment(){
  HexuDemoPaymentGateway demo=new HexuDemoPaymentGateway(db,finance);
  String realOrder=create(1);pay(realOrder);
  long realLine=number(db.one("select id from hx_order_line where order_id=?",realOrder),"id");
  String realRefund=str(finance.refundApply(customer,realOrder,map("lineId",realLine,"qty",1,"type","REFUND_ONLY","reason","测试退款")),"id");
  finance.refundReview(owner,realRefund,map("approve",true));
  Map<String,Object> real=db.one("select * from hx_refund where id=?",realRefund);
  assertThrows(BusinessException.class,()->demo.submitRefund(real,str(db.one("select merchant_no from hx_order where id=?",realOrder),"merchant_no")));
  assertEquals("APPROVED",db.one("select status from hx_refund where id=?",realRefund).get("status"));

  String order=create(1);Map<String,Object>payment=orders.payment(customer,order);
  orders.paymentConfirmed(str(payment,"id"),str(payment,"merchant_no"),number(payment,"amount"),"DEMO-"+payment.get("id"));
  long line=number(db.one("select id from hx_order_line where order_id=?",order),"id");
  String refund=str(finance.refundApply(customer,order,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","演示退款")),"id");
  finance.refundReview(owner,refund,map("approve",true));
  Map<String,Object> row=db.one("select * from hx_refund where id=?",refund);
  assertEquals("SUCCESS",demo.submitRefund(row,str(payment,"merchant_no")).get("status"));
  assertEquals("SUCCESS",demo.submitRefund(row,str(payment,"merchant_no")).get("status"));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='DEMO_REFUND' and reference_id=?",Integer.class,refund));
 }
 @Test void demoPayoutKeepsReviewReasonAndMarksTheResultAsSimulated(){
  String order=create(1);pay(order);orders.ship(owner,order,map("carrier","顺丰","tracking","SF123456789"));orders.receive(customer,order);
  String id=str(finance.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104")),"id");
  finance.reviewWithdrawal(owner,id,true,"财务审核通过");
  HexuDemoPaymentGateway demo=new HexuDemoPaymentGateway(db,finance);
  Map<String,Object> row=db.one("select * from hx_withdrawal where id=?",id);
  Map<String,Object> result=demo.submitPayout(row,str(row,"merchant_no"));
  assertEquals("PAID",result.get("status"));
  assertEquals("财务审核通过",db.one("select reason from hx_withdrawal where id=?",id).get("reason"));
  assertEquals("演示到账，未发生真实转账",db.one("select payout_reason from hx_withdrawal where id=?",id).get("payout_reason"));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='DEMO_PAYOUT' and reference_id=?",Integer.class,id));
 }
 void complete(String id){pay(id);orders.ship(owner,id,map("carrier","顺丰","tracking","SF123456789"));orders.receive(customer,id);}
 long wallet(long member){return db.jdbc().queryForObject("select available from hx_wallet where shop_id=2 and member_id=?",Long.class,member);}
 @Test void pricesAreServerScoped(){for(long[] test:new long[][]{{101,600},{102,800},{103,1000},{201,2000}}){Map<String,Object>p=catalog.products(new Actor(test[0],false,false),2,false).stream().filter(x->"stapler".equals(x.get("id"))).findFirst().get();assertEquals(test[1],number(p,"price"));assertEquals(2000,number(p,"retailPrice"));assertFalse(p.containsKey("owner_price"));assertFalse(p.containsKey("center_price"));}assertThrows(BusinessException.class,()->catalog.products(owner,3,true));}
 @Test void wholesalePricesUseTheDestinationShopRank(){
  db.jdbc().update("insert into hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) values(4,'第二经营商城','测试县域','DEALER',101,'SANDBOX-SECOND',true,'{}')");
  db.jdbc().update("insert into hx_agent(id,member_id,shop_id,parent_id,rank_no) values(30,101,4,null,2)");
  assertEquals(600,number(catalog.products(owner,900,false).stream().filter(p->"stapler".equals(p.get("id"))).findFirst().orElseThrow(),"price"));
  assertEquals(600,number(catalog.products(owner,900,false,2L).stream().filter(p->"stapler".equals(p.get("id"))).findFirst().orElseThrow(),"price"));
  assertEquals(800,number(catalog.products(owner,900,false,4L).stream().filter(p->"stapler".equals(p.get("id"))).findFirst().orElseThrow(),"price"));
  assertThrows(BusinessException.class,()->catalog.products(owner,900,false,3L));
  assertThrows(BusinessException.class,()->catalog.products(customer,900,false,4L));
 }
 @Test void wholesaleOrderRejectsChangedConfirmedAmount(){
  Map<String,Object> body=map("destinationShopId",2,"items",List.of(map("id","stapler","qty",48)),"address",map("name","采购人","detail","测试仓库"),"expectedTotal",28800);
  db.jdbc().update("update hx_sku set owner_price=500 where shop_id=900 and id='stapler'");
  assertThrows(BusinessException.class,()->orders.create(owner,900,body));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_order where shop_id=900 and buyer_id=101",Integer.class));
  body.put("expectedTotal",24000);
  assertEquals(24000,number(orders.create(owner,900,body),"total"));
 }
 @Test void guestCatalogExposesOnlyPublicRetailFields(){
  Map<String,Object> product=catalog.guestProducts(2).stream().filter(x->"stapler".equals(x.get("id"))).findFirst().orElseThrow(AssertionError::new);
  assertEquals(2000,number(product,"price"));
  for(String secret:Arrays.asList("cloud_price","center_price","owner_price","available","stock","locked","in_transit"))assertFalse(product.containsKey(secret),secret);
  assertThrows(BusinessException.class,()->catalog.guestProducts(900));
  db.jdbc().update("update hx_shop set status='INACTIVE' where id=2");
  assertThrows(BusinessException.class,()->catalog.guestProducts(2));
 }
 @Test void productBrandIsConfigurableAndVisibleToMemberAndGuest(){Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=2 and id='stapler'"));p.put("brand","  清风  ");catalog.saveSku(owner,2,p);assertEquals("清风",catalog.products(customer,2,false).stream().filter(x->"stapler".equals(x.get("id"))).findFirst().orElseThrow().get("brand"));assertEquals("清风",catalog.guestProducts(2).stream().filter(x->"stapler".equals(x.get("id"))).findFirst().orElseThrow().get("brand"));p.put("brand","无效\n品牌");assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p));p.put("brand",String.join("",Collections.nCopies(81,"牌")));assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p));assertEquals("清风",db.one("select brand from hx_sku_detail where shop_id=2 and sku_id='stapler'").get("brand"));}
 @Test void productNameAndSpecRejectControlCharactersWithoutChangingSku(){Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=2 and id='stapler'"));String originalName=str(p,"name"),originalSpec=str(p,"spec");for(String invalid:List.of("\u00a0\u2003","错误\n名称","错误\u0085名称")){p.put("name",invalid);assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p));}p.put("name",originalName);p.put("spec","规格\t注入");assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p));Map<String,Object>saved=db.one("select name,spec from hx_sku where shop_id=2 and id='stapler'");assertEquals(originalName,saved.get("name"));assertEquals(originalSpec,saved.get("spec"));}
 @Test void addressUsageAndRegionPersistWithFieldValidation(){
  Map<String,Object> address=map("name","  测试收货人  ","phone","13812345678","region","福建省 泉州市 安溪县","detail"," 测试街道1号 ","usage","公司","consent",true,"primary",true);
  String id=str(workflow.save(customer,2,"address",address,false),"id");
  Map<String,Object> saved=workflow.list(customer,2,"address",false).stream().filter(row->id.equals(row.get("id"))).findFirst().orElseThrow();
  Map<String,Object> body=(Map<String,Object>)saved.get("body");
  assertEquals("测试收货人",body.get("name"));assertEquals("福建省 泉州市 安溪县",body.get("region"));
  assertEquals("测试街道1号",body.get("detail"));assertEquals("公司",body.get("usage"));
  int addressCount=db.jdbc().queryForObject("select count(*) from hx_document where kind='address' and member_id=201",Integer.class);
  int auditCount=db.jdbc().queryForObject("select count(*) from hx_audit where action='SAVE_address' and actor_id=201",Integer.class);
  for(Map<String,Object> invalid:Arrays.asList(
   map("name","测试人","phone","13812345678","region","","detail","测试地址","usage","家","consent",true),
   map("name","测试人","phone","13812345678","region","福建省 泉州市","detail","测试地址","usage","虚构用途","consent",true),
   map("name","测试人","phone","13812345678","region","福建省 泉州市","detail","测试地址","usage","家","consent",false),
   map("name","测试人","phone","13812345678","region","火星省 土星市 木星区","detail","测试地址","usage","家","consent",true),
   map("name","测试人","phone","13812345678","region","浙江省 泉州市 安溪县","detail","测试地址","usage","家","consent",true),
   map("name","测试人","phone","13812345678","region","福建省 泉州市","detail","测试地址\n注入","usage","家","consent",true)))
   assertThrows(BusinessException.class,()->workflow.save(customer,2,"address",invalid,false));
  assertEquals(addressCount,db.jdbc().queryForObject("select count(*) from hx_document where kind='address' and member_id=201",Integer.class));
  assertEquals(auditCount,db.jdbc().queryForObject("select count(*) from hx_audit where action='SAVE_address' and actor_id=201",Integer.class));
 }
 @Test void browseHistoryIsRecentDeduplicatedAndMemberShopScoped(){
  assertEquals(Arrays.asList("paper"),workflow.recordBrowse(customer,2,"paper").get("ids"));
  assertEquals(Arrays.asList("oil","paper"),workflow.recordBrowse(customer,2,"oil").get("ids"));
  assertEquals(Arrays.asList("paper","oil"),workflow.recordBrowse(customer,2,"paper").get("ids"));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_document where kind='browse_history' and member_id=201 and shop_id=2 and status='ACTIVE'",Integer.class));
  assertTrue(workflow.list(new Actor(202,false,false),2,"browse_history",false).isEmpty());
  assertTrue(workflow.list(customer,1,"browse_history",false).isEmpty());
  assertThrows(BusinessException.class,()->workflow.recordBrowse(customer,2,"missing-sku"));
  assertThrows(BusinessException.class,()->workflow.recordBrowse(customer,2,"../paper"));
  assertEquals(Arrays.asList("paper","oil"),((Map<?,?>)workflow.list(customer,2,"browse_history",false).get(0).get("body")).get("ids"));
 }
 @Test void couponListShowsRemainingQuantityAfterLimitReached(){
  Actor other=new Actor(202,false,false);
  String campaign=str(workflow.save(owner,2,"coupon",map("name","限量测试券","discount",100,"threshold",1000,"quantity",1,"expiresAt",System.currentTimeMillis()+86400000),true),"id");
  Map<String,Object> before=workflow.coupons(other,2).stream().filter(row->campaign.equals(row.get("id"))).findFirst().orElseThrow();
  assertEquals(1,number(before,"remainingQuantity"));
  workflow.claim(customer,2,campaign);
  Map<String,Object> after=workflow.coupons(other,2).stream().filter(row->campaign.equals(row.get("id"))).findFirst().orElseThrow();
  assertEquals(0,number(after,"remainingQuantity"));
  assertThrows(BusinessException.class,()->workflow.claim(other,2,campaign));
 }
 @Test void unshippedDiscountedRefundRestocksOnce(){
  String campaign=str(workflow.save(owner,2,"coupon",map("name","未发货退款测试券","discount",100,"threshold",1000,"quantity",1,"expiresAt",System.currentTimeMillis()+86400000),true),"id");
  String claim=str(workflow.claim(customer,2,campaign),"id");
  Map<String,Object> request=body("stapler",1);request.put("couponId",claim);
  String order=str(orders.create(customer,2,request),"id");
  pay(order);
  assertEquals(319,number(db.one("select available from hx_sku where shop_id=2 and id='stapler'"),"available"));
  long line=number(db.one("select id from hx_order_line where order_id=?",order),"id");
  String refund=str(finance.refundApply(customer,order,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","未发货退款")),"id");
  assertEquals(1900,number(db.one("select amount from hx_refund where id=?",refund),"amount"));
  finance.refundReview(owner,refund,map("approve",true));
  finance.refundConfirmed(refund);finance.refundConfirmed(refund);
  assertEquals(320,number(db.one("select available from hx_sku where shop_id=2 and id='stapler'"),"available"));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_inventory_log where reference_id=? and reason='REFUND_UNSHIPPED'",Integer.class,refund));
  assertEquals(1,number(db.one("select restock_qty from hx_refund where id=?",refund),"restock_qty"));
  assertEquals(1900,number(db.one("select refunded from hx_order where id=?",order),"refunded"));
  assertEquals("REFUNDED",str(db.one("select status from hx_order where id=?",order),"status"));
 }
 @Test void firstInviteCannotBeOverwritten(){db.jdbc().update("delete from hx_binding where member_id=201");Map<String,Object>invite=catalog.invite(new Actor(103,false,false),2);assertEquals(3,number(catalog.bind(customer,2,str(invite,"invite")),"agent_id"));assertEquals(3,number(catalog.bind(customer,2,str(catalog.invite(c1,2),"invite")),"agent_id"));db.jdbc().update("delete from hx_binding where member_id=201");assertEquals(1,number(catalog.bind(customer,2,""),"agent_id"));}
 @Test void c1SettlementAndPartialRefundAreBalanced(){String id=create(2);complete(id);assertEquals(2000,wallet(104));assertEquals(60,wallet(103));assertEquals(400,wallet(102));assertEquals(340,wallet(101));long line=db.jdbc().queryForObject("select id from hx_order_line where order_id=?",Long.class,id);Map<String,Object>r=finance.refundApply(customer,id,map("lineId",line,"qty",1,"type","RETURN","reason","商品破损"));String refund=str(r,"id");finance.refundReview(owner,refund,map("approve",true));finance.inspect(owner,refund,1);finance.refundConfirmed(refund);finance.refundConfirmed(refund);assertEquals(1000,wallet(104));assertEquals(30,wallet(103));assertEquals(200,wallet(102));assertEquals(170,wallet(101));assertEquals(319,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));}
 @Test void oldRulesAndPricesStayInSnapshot(){String id=create(1);db.jdbc().update("update hx_rule set cloud_bps=400");db.jdbc().update("update hx_sku set cloud_price=1100 where shop_id=2 and id='stapler'");complete(id);assertEquals(30,wallet(103));assertEquals(1000,wallet(104));}
 @Test void paymentRoutingAndRepeatedCallbacks(){String id=create(1);Map<String,Object>p=orders.payment(customer,id);assertEquals("SANDBOX-ANXI",p.get("merchant_no"));assertThrows(BusinessException.class,()->orders.paymentConfirmed(str(p,"id"),"SANDBOX-COMPANY",2000,"BAD"));orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),2000,"OK");orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),2000,"OK");assertThrows(BusinessException.class,()->orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),2000,"SECOND_RECEIPT"));assertEquals("OK",db.one("select channel_ref from hx_payment where id=?",p.get("id")).get("channel_ref"));assertEquals(319,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_inventory_log where reference_id=? and reason='PAID'",Integer.class,id));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_ledger where category='RECEIPT'",Integer.class));}
 @Test void orderDetailUsesConfirmedReceiptTimeNotUnpaidOrderCreationTime(){
  String id=create(1);
  assertFalse(orders.detail(customer,id,false).containsKey("paid_at"));
  Map<String,Object> payment=orders.payment(customer,id);
  orders.paymentConfirmed(str(payment,"id"),str(payment,"merchant_no"),number(payment,"amount"),"PAID-AT-TEST");
  Object paidAt=db.one("select created_at from hx_ledger where reference_id=? and category='RECEIPT'",id).get("created_at");
  assertEquals(paidAt,orders.detail(customer,id,false).get("paid_at"));
  assertEquals(paidAt,orders.detail(owner,id,true).get("paid_at"));
 }
 @Test void cancelledOrderReturnsInventoryAndPoints(){Map<String,Object>b=body("stapler",2);b.put("points",200);String id=str(orders.create(customer,2,b),"id");orders.cancel(customer,id);orders.cancel(customer,id);assertEquals(320,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));assertEquals(2480,db.jdbc().queryForObject("select available from hx_points where member_id=201 and shop_id=0",Long.class));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where reference_id=? and action='ORDER_CANCEL' and actor_id=201",Integer.class,id));}
 @Test void receivingOrderAuditsAndAwardsExactlyOnce(){String id=create(1);pay(id);orders.ship(owner,id,map("carrier","顺丰","tracking","SF123456789"));orders.receive(customer,id);orders.receive(customer,id);assertEquals("COMPLETED",str(db.one("select status from hx_order where id=?",id),"status"));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where reference_id=? and action='ORDER_RECEIVE' and actor_id=201",Integer.class,id));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_point_batch where origin_ref=? and origin_kind='ORDER_REWARD'",Integer.class,id));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_message where reference_id=? and title='订单完成，收益已结算'",Integer.class,id));}
 @Test void expiredOrderReleasesInventoryAndAuditsOnce(){String id=create(1);db.jdbc().update("update hx_order set expires_at=? where id=?",new java.sql.Timestamp(System.currentTimeMillis()-1000),id);assertEquals(1,orders.expire());assertEquals(0,orders.expire());assertEquals("CANCELLED",db.one("select status from hx_order where id=?",id).get("status"));assertEquals(320,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where reference_id=? and action='ORDER_EXPIRE' and actor_id=0",Integer.class,id));}
 @Test void transferIsAtomicAndRespectsScope(){Map<String,Object>r=finance.transfer(customer,0,map("recipientId",202,"amount",100));assertEquals(0,db.jdbc().queryForObject("select sum(amount) from hx_point_log where reference_id=?",Long.class,r.get("reference")));assertEquals(2380,db.jdbc().queryForObject("select available from hx_points where shop_id=0 and member_id=201",Long.class));assertThrows(BusinessException.class,()->finance.transfer(customer,0,map("recipientId",202,"amount",1001)));assertThrows(BusinessException.class,()->finance.transfer(customer,2,map("recipientId",401,"amount",100)));}
 @Test void withdrawalApprovalIsNotPaymentAndFailureReleases(){String id=create(1);complete(id);Map<String,Object>w=finance.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104"));assertEquals(6,number(w,"fee"));assertEquals(994,number(w,"net"));assertEquals(0,wallet(104));String withdrawal=str(w,"id");assertThrows(BusinessException.class,()->finance.reviewWithdrawal(new Actor(401,false,false),withdrawal,true,""));assertEquals("APPROVED",finance.reviewWithdrawal(owner,withdrawal,true,"").get("status"));finance.payoutResult(withdrawal,false,"","渠道拒绝");finance.payoutResult(withdrawal,false,"","重复回调");assertEquals(1000,wallet(104));}
 @Test void ownerCannotReviewOwnWithdrawal(){
  // 独立 H2 沙盒：店主同时是代理，结算账户由平台审核，余额只在测试库内预置。
  String account=str(workflow.save(owner,2,"settlement_account",map("channel","BALANCE","accountName","沙盒店主"),false),"id");
  workflow.review(platform,account,map("decision","APPROVED"));
  db.jdbc().update("update hx_wallet set available=500 where shop_id=2 and member_id=?",owner.id);
  String withdrawal=str(finance.withdraw(owner,2,map("amount",100,"channel","BALANCE","accountRef",account)),"id");
  Map<String,Object> before=db.one("select * from hx_wallet where shop_id=2 and member_id=?",owner.id);
  assertThrows(BusinessException.class,()->finance.reviewWithdrawal(owner,withdrawal,true,""));
  assertThrows(BusinessException.class,()->finance.reviewWithdrawal(owner,withdrawal,false,"自己驳回"));
  Map<String,Object> pending=db.one("select * from hx_withdrawal where id=?",withdrawal);
  assertEquals("PENDING",pending.get("status"));assertNull(pending.get("reviewer"));
  assertEquals(before,db.one("select * from hx_wallet where shop_id=2 and member_id=?",owner.id));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_audit where reference_id=? and action='WITHDRAW_REVIEW'",Integer.class,withdrawal));
  assertEquals("APPROVED",finance.reviewWithdrawal(platform,withdrawal,true,"").get("status"));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where reference_id=? and action='WITHDRAW_REVIEW'",Integer.class,withdrawal));
  assertEquals(100,number(db.one("select * from hx_wallet where shop_id=2 and member_id=?",owner.id),"frozen"));
 }
 @Test void realPaymentModeRejectsUnavailablePayoutBeforeFreezingFunds(){
  complete(create(1));
  HexuFinanceService gated=new HexuFinanceService(db);
  org.springframework.test.util.ReflectionTestUtils.setField(gated,"realPaymentEnabled",true);
  org.springframework.test.util.ReflectionTestUtils.setField(gated,"payoutGateway",new UnconfiguredPaymentGateway());
  int before=db.jdbc().queryForObject("select count(*) from hx_withdrawal",Integer.class);
  assertThrows(BusinessException.class,()->gated.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104")));
  assertEquals(before,db.jdbc().queryForObject("select count(*) from hx_withdrawal",Integer.class));
  assertEquals(1000,wallet(104));
  String legacy=str(finance.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104")),"id");
  assertThrows(BusinessException.class,()->gated.reviewWithdrawal(owner,legacy,true,""));
  assertEquals("REJECTED",gated.reviewWithdrawal(owner,legacy,false,"渠道未开通").get("status"));
  assertEquals(1000,wallet(104));
 }
 @Test void realPaymentModeBankPathRequiresExplicitFlagEvidenceAndDualControl(){
  complete(create(1));
  HexuFinanceService gated=new HexuFinanceService(db);
  org.springframework.test.util.ReflectionTestUtils.setField(gated,"realPaymentEnabled",true);
  org.springframework.test.util.ReflectionTestUtils.setField(gated,"bankPayoutEnabled",false);
  org.springframework.test.util.ReflectionTestUtils.setField(gated,"bankPayoutRequireDualControl",true);
  assertThrows(BusinessException.class,()->gated.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104")));
  org.springframework.test.util.ReflectionTestUtils.setField(gated,"bankPayoutEnabled",true);
  Map<String,Object>w=gated.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104"));
  String withdrawal=str(w,"id");gated.reviewWithdrawal(owner,withdrawal,true,"");
  db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values('BANK_RECEIPT_FAIL',2,1,'FINANCE','image/png',?,?)",new byte[12],new byte[]{1});
  Map<String,Object>failed=gated.bankPayoutResult(platform,withdrawal,false,map("bankReference","BANK-FAIL-1","reason","银行回单显示收款失败","evidenceIds",List.of("BANK_RECEIPT_FAIL")));
  assertEquals("FAILED",failed.get("status"));assertEquals(1000,wallet(104));assertEquals(0,number(db.one("select * from hx_wallet where shop_id=2 and member_id=104"),"frozen"));
  assertThrows(BusinessException.class,()->gated.bankPayoutResult(owner,withdrawal,false,map("bankReference","BANK-FAIL-1","reason","重复登记","evidenceIds",List.of("BANK_RECEIPT_FAIL"))));
  complete(create(1));
  Map<String,Object>second=gated.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104"));String secondId=str(second,"id");gated.reviewWithdrawal(owner,secondId,true,"");
  db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values('BANK_RECEIPT_OK',2,1,'FINANCE','image/png',?,?)",new byte[12],new byte[]{1});
  Map<String,Object>paid=gated.bankPayoutResult(platform,secondId,true,map("bankReference","BANK-OK-1","reason","已核对银行回单和收款账户","evidenceIds",List.of("BANK_RECEIPT_OK")));
  assertEquals("PAID",paid.get("status"));assertEquals(1000,wallet(104));assertEquals(0,number(db.one("select * from hx_wallet where shop_id=2 and member_id=104"),"frozen"));
  Map<String,Object>duplicate=gated.bankPayoutResult(platform,secondId,true,map("bankReference","BANK-OK-1","reason","重复回放","evidenceIds",List.of("BANK_RECEIPT_OK")));
  assertEquals("PAID",duplicate.get("status"));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_ledger where reference_id=? and category='PAYOUT'",Integer.class,secondId));
  assertThrows(BusinessException.class,()->gated.bankPayoutResult(platform,secondId,false,map("bankReference","BANK-OK-1","reason","冲突回放","evidenceIds",List.of("BANK_RECEIPT_OK"))));
 }
 @Test void wholesaleChecksBoxesAndDirectShipDoesNotStockDestination(){Map<String,Object>bad=body("stapler",1);bad.put("destinationShopId",2);assertThrows(BusinessException.class,()->orders.create(owner,900,bad));Map<String,Object>b=body("stapler",48);b.put("destinationShopId",2);b.put("directShip",true);Map<String,Object>o=orders.create(owner,900,b);assertEquals(28800,number(o,"total"));assertEquals("SANDBOX-COMPANY",o.get("merchant_no"));Map<String,Object>p=orders.payment(owner,str(o,"id"));orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),28800,"WHOLESALE");orders.ship(platform,str(o,"id"),map("carrier","顺丰","tracking","SF123456789"));orders.receive(owner,str(o,"id"));assertEquals(320,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));}
 @Test void concurrentOrdersCannotOversell() throws Exception {body("stapler",1); // 并发下单前先安装隔离协议夹具，避免夹具自身争抢插入。
  db.jdbc().update("update hx_sku set available=1 where id='stapler' and shop_id=2");ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->{try{create(1);return true;}catch(BusinessException e){return false;}}));int success=0;for(Future<Boolean>f:results)if(f.get(10,TimeUnit.SECONDS))success++;assertEquals(1,success);assertEquals(0,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));}finally{pool.shutdownNow();}}
 @Test void failedCommandRollsBackAndReplayDoesNotRepeat(){Map<String,Object>b=body("stapler",1);Object first=commands.execute(customer,"request-0001","create",b,()->orders.create(customer,2,b));Object second=commands.execute(customer,"request-0001","create",b,()->{fail("重试不应再次执行");return null;});assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_order",Integer.class));assertThrows(BusinessException.class,()->commands.execute(customer,"request-0001","create",body("stapler",2),()->null));}
 @Test void centsRoundingPreservesPeerCost(){assertEquals(30,SettlementCalculator.proportion(1000,300,10000));assertEquals(16,SettlementCalculator.proportion(800,200,10000));assertEquals(6,SettlementCalculator.proportion(600,100,10000));assertEquals(600,SettlementCalculator.proportion(100000,60,10000));}
 @Test void couponCannotBeClaimedAgainAfterPaymentOrCancellation(){
  String campaign=str(workflow.save(owner,2,"coupon",map("name","优惠券","discount",100,"threshold",2000,"quantity",1,"expiresAt",System.currentTimeMillis()+86400000),true),"id");
  String coupon=str(workflow.claim(customer,2,campaign),"id");Map<String,Object>b=body("stapler",1);b.put("couponId",coupon);String id=str(orders.create(customer,2,b),"id");orders.cancel(customer,id);
  assertThrows(BusinessException.class,()->workflow.claim(customer,2,campaign));assertThrows(BusinessException.class,()->workflow.claim(new Actor(202,false,false),2,campaign));
  String second=str(orders.create(customer,2,b),"id");pay(second);assertThrows(BusinessException.class,()->workflow.claim(customer,2,campaign));assertEquals("USED",db.one("select status from hx_document where id=?",coupon).get("status"));
 }
 @Test void orderListsContainRealItemsWithoutPrivatePriceSnapshot(){String id=create(2);Map<String,Object>o=orders.list(customer,2,false).get(0);List<?> items=(List<?>)o.get("items");assertEquals(1,items.size());assertEquals(2,number((Map<String,Object>)items.get(0),"qty"));assertFalse(((Map<?,?>)items.get(0)).containsKey("snapshot_json"));assertThrows(BusinessException.class,()->orders.detail(new Actor(202,false,false),id,false));}
 @Test void concurrentPartialRefundsAreNotReservedTwice(){String id=create(2);pay(id);long line=db.jdbc().queryForObject("select id from hx_order_line where order_id=?",Long.class,id);Map<String,Object>b=map("lineId",line,"qty",1,"type","PARTIAL","reason","部分破损");Map<String,Object>r=finance.refundApply(customer,id,b);assertThrows(BusinessException.class,()->finance.refundApply(customer,id,b));finance.cancelRefund(customer,str(r,"id"));assertEquals("售后申请已关闭，不能重复撤销",assertThrows(BusinessException.class,()->finance.cancelRefund(customer,str(r,"id"))).getMessage());assertNotNull(finance.refundApply(customer,id,b));}
 @Test void exchangeUsesInventoryButDoesNotReverseEarnings(){String id=create(1);complete(id);long line=db.jdbc().queryForObject("select id from hx_order_line where order_id=?",Long.class,id);String ref=str(finance.refundApply(customer,id,map("lineId",line,"qty",1,"type","EXCHANGE","reason","商品破损")),"id");finance.refundReview(owner,ref,map("approve",true));finance.returnTracking(customer,ref,map("carrier","顺丰","tracking","SF123456789"));assertEquals("退货运单已提交，不能撤销",assertThrows(BusinessException.class,()->finance.cancelRefund(customer,ref)).getMessage());finance.inspect(owner,ref,0);finance.exchangeShip(owner,ref,map("carrier","顺丰","tracking","SF987654321"));assertEquals(318,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));assertThrows(BusinessException.class,()->finance.exchangeShip(owner,ref,map("carrier","顺丰","tracking","SF987654321")));assertEquals("CLOSED",finance.exchangeReceive(customer,ref).get("status"));assertEquals(1000,wallet(104));}
 @Test void warehouseCannotReadFinancialDataOrSettlementAccounts(){db.jdbc().update("insert into hx_staff values(700,2,'WAREHOUSE')");Actor warehouse=new Actor(700,false,true);assertNotNull(workflow.managementList(warehouse,2,"inventory"));assertThrows(BusinessException.class,()->workflow.managementList(warehouse,2,"ledger"));assertThrows(BusinessException.class,()->workflow.list(warehouse,2,"settlement_account",true));assertThrows(BusinessException.class,()->workflow.managementList(warehouse,3,"inventory"));assertEquals(8,catalog.products(platform,900,true).size());}
 @Test void configurableFreightAndDailyCheckinAreAppliedOnce(){workflow.save(owner,2,"freight",map("name","默认运费","freightCents",600,"freeThreshold",4000),true);assertEquals(2600,number(orders.create(customer,2,body("stapler",1)),"total"));assertEquals(4000,number(orders.create(customer,2,body("stapler",2)),"total"));workflow.checkin(customer,2);assertThrows(BusinessException.class,()->workflow.checkin(customer,2));assertEquals(2485,db.jdbc().queryForObject("select available from hx_points where shop_id=0 and member_id=201",Long.class));}
 @Test void checkinUsesShanghaiDayAndRecognizesLegacyUtcDateEntry(){assertEquals(java.time.LocalDate.of(2026,10,1),HexuWorkflowService.checkinDay(java.time.Instant.parse("2026-10-01T15:59:59Z")));assertEquals(java.time.LocalDate.of(2026,10,2),HexuWorkflowService.checkinDay(java.time.Instant.parse("2026-10-01T16:00:00Z")));db.points(0,customer.id,5,"CHECKIN","2000-01-01",false);assertEquals(0,number(workflow.checkinStatus(customer,2),"platform"));assertThrows(BusinessException.class,()->workflow.checkin(customer,2));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_point_log where member_id=201 and shop_id=0 and kind='CHECKIN'",Long.class));}
 @Test void frozenPointsDoNotPreventFinancialRefundCorrections(){String id=create(1);complete(id);db.points(0,201,-2500,"TEST_SPEND",id,false);db.jdbc().update("update hx_points set frozen=true where shop_id=0 and member_id=201");long line=db.jdbc().queryForObject("select id from hx_order_line where order_id=?",Long.class,id);String ref=str(finance.refundApply(customer,id,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","退款测试")),"id");finance.refundReview(owner,ref,map("approve",true));finance.refundConfirmed(ref);assertEquals(20,db.jdbc().queryForObject("select pending_reclaim from hx_points where shop_id=0 and member_id=201",Long.class));assertEquals(0,wallet(104));}
 @Test void promotedTeamMovesOnlyNewOrdersAndKeepsApprovedPeerIncome(){
  String old=create(1);db.jdbc().update("update hx_agent set rank_no=3 where id=2");
  db.jdbc().update("insert into hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,status,settings) values(901,'新店','安溪县','DEALER',102,'SANDBOX-NEW',true,'ACTIVE','{}')");
  db.jdbc().update("insert into hx_sku(id,shop_id,name,retail,cloud_price,center_price,owner_price,available) select id,901,name,retail,cloud_price,center_price,owner_price,available from hx_sku where shop_id=2");
  String ref=str(agency.propose(owner,2,map("rootAgentId",2,"targetShopId",901,"effectiveAt",new java.sql.Timestamp(System.currentTimeMillis()-1000).toString())),"id");
  agency.review(platform,ref,map("approve",true));assertEquals(901,number(db.one("select * from hx_binding where member_id=201"),"shop_id"));assertEquals(2,number(db.one("select * from hx_order where id=?",old),"shop_id"));complete(old);assertEquals(170,wallet(101));
  Map<String,Object>newOrder=orders.create(customer,901,body("stapler",1));Map<String,Object>p=orders.payment(customer,str(newOrder,"id"));orders.paymentConfirmed(str(p,"id"),"SANDBOX-NEW",2000,"NEW-SHOP");orders.ship(new Actor(102,false,false),str(newOrder,"id"),map("carrier","顺丰","tracking","SF123456789"));orders.receive(customer,str(newOrder,"id"));
  assertEquals(6,db.jdbc().queryForObject("select available from hx_wallet where shop_id=901 and member_id=101",Long.class));assertEquals(364,db.jdbc().queryForObject("select available from hx_wallet where shop_id=901 and member_id=102",Long.class));assertEquals(1000,db.jdbc().queryForObject("select available from hx_wallet where shop_id=901 and member_id=104",Long.class));
 }
 @Test void bAndCReceiveTheirOwnRetailMargin(){db.jdbc().update("update hx_binding set agent_id=2 where member_id=201 and shop_id=2");complete(create(1));assertEquals(1200,wallet(102));assertEquals(200,wallet(101));db.jdbc().update("update hx_binding set agent_id=3 where member_id=201 and shop_id=2");complete(create(1));assertEquals(1000,wallet(103));assertEquals(1400,wallet(102));assertEquals(400,wallet(101));}
 @Test void mixedWholesaleRespectsGroupCapacityAndMinimumBoxes(){db.jdbc().update("update hx_shop set settings=? where id=900",db.json(map("mixedEnabled",true,"mixCapacity",24,"minMixBoxes",2)));db.jdbc().update("update hx_sku set mix_group='A',mix_units=1 where shop_id=900 and id in ('stapler','cup')");Map<String,Object>b=map("items",Arrays.asList(map("id","stapler","qty",23),map("id","cup","qty",25)),"destinationShopId",2,"address",map("name","采购人","detail","测试仓"));assertNotNull(orders.create(owner,900,b));b.put("items",Arrays.asList(map("id","stapler","qty",23),map("id","cup","qty",24)));assertThrows(BusinessException.class,()->orders.create(owner,900,b));}
 @Test void crossShopPolicyDoesNotCarryTheOriginalEarningChain(){assertFalse(bool(catalog.access(customer,3),"allowed"));assertThrows(BusinessException.class,()->orders.create(customer,3,body("stapler",1)));workflow.save(platform,3,"cross_shop_policy",map("mode","ALLOW_RETAIL"),true);Map<String,Object>o=orders.create(customer,3,body("stapler",1));assertEquals(2000,number(o,"total"));assertEquals(20,db.jdbc().queryForObject("select agent_id from hx_earning where order_id=?",Long.class,o.get("id")));}
 @Test void approvedCrossPurchaseGetsOnlyTheApprovedRankPrice(){Map<String,Object> d=workflow.save(c1,3,"cross_purchase",map("sourceShopId",2,"items",List.of(map("skuId","stapler","qty",2)),"expiresAt",System.currentTimeMillis()+86400000),false);workflow.review(platform,str(d,"id"),map("decision","APPROVED"));assertEquals(1,catalog.rank(c1,3));Map<String,Object>o=orders.create(c1,3,body("stapler",1));assertEquals(1000,number(o,"total"));assertEquals(20,db.jdbc().queryForObject("select agent_id from hx_earning where order_id=?",Long.class,o.get("id")));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_earning where order_id=?",Integer.class,o.get("id")));}
 @Test void rewardRulesAreSnapshottedAndUnsignedChannelIsRejected(){String id=create(1);workflow.save(owner,2,"points_rule",map("purchaseRate",3,"deductionPercent",20,"checkinPoints",5),true);complete(id);assertEquals(20,number(db.one("select points_earned from hx_order where id=?",id),"points_earned"));PaymentGateway gateway=new HexuGatewayConfiguration().paymentGateway();assertThrows(BusinessException.class,()->gateway.verifyNotification("{}",Collections.emptyMap()));assertThrows(BusinessException.class,()->new HexuGatewayConfiguration().wechatIdentityGateway().exchangeCode("untrusted-code"));}
 @Test void redemptionIsAnOrderAndRefundReturnsPointsWithoutCash(){
  Map<String,Object>b=map("skuId","cup","qty",1,"scope",0,"address",map("name","兑换用户","detail","测试地址"));assertThrows(BusinessException.class,()->orders.redeem(customer,2,b));b.put("skuId","towel");Map<String,Object>o=orders.redeem(customer,2,b);assertEquals(0,number(o,"total"));assertEquals(0,number(o,"points_scope"));assertEquals("PAID",o.get("status"));assertEquals(680,db.jdbc().queryForObject("select available from hx_points where member_id=201 and shop_id=0",Long.class));
  long line=db.jdbc().queryForObject("select id from hx_order_line where order_id=?",Long.class,o.get("id"));String ref=str(finance.refundApply(customer,str(o,"id"),map("lineId",line,"qty",1,"type","RETURN","reason","兑换退货")),"id");Map<String,Object>read=new HexuReadModels(db).refunds(customer,2,false).stream().filter(row->ref.equals(row.get("id"))).findFirst().orElseThrow();assertEquals(1800,number(read,"points_return"));assertEquals(0,number(read,"points_scope"));finance.refundReview(owner,ref,map("approve",true));finance.inspect(owner,ref,1);assertEquals("SUCCESS",db.one("select status from hx_refund where id=?",ref).get("status"));assertEquals(2480,db.jdbc().queryForObject("select available from hx_points where member_id=201 and shop_id=0",Long.class));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_ledger",Integer.class));
 }
 @Test void scheduledCustomerMigrationWaitsAndLeavesOldSnapshot(){String old=create(1);String ref=str(workflow.save(customer,2,"migration",map("targetShopId",2,"targetAgentId",3,"effectiveAt",new java.sql.Timestamp(System.currentTimeMillis()+86400000).toString()),false),"id");workflow.review(owner,ref,map("decision","APPROVED"));workflow.applyScheduledMigrations();assertEquals(4,number(db.one("select * from hx_binding where member_id=201 and shop_id=2"),"agent_id"));Map<String,Object>d=db.one("select * from hx_document where id=?",ref);Map<String,Object>b=db.parse(d.get("body_json"));b.put("effectiveAt",new java.sql.Timestamp(System.currentTimeMillis()-1000).toString());db.jdbc().update("update hx_document set body_json=? where id=?",db.json(b),ref);workflow.applyScheduledMigrations();assertEquals(3,number(db.one("select * from hx_binding where member_id=201 and shop_id=2"),"agent_id"));complete(old);assertEquals(1000,wallet(104));}
 @Test void sensitiveFinancialIdentifiersAreEncryptedAndMasked(){Map<String,Object>b=map("bank","6222000000000000","accountName","测试账户");SensitiveFields.seal(b,"local-test-encryption-key-32-characters");assertEquals("****0000",b.get("bank"));assertFalse(str(b,"_secure_bank").contains("6222000000000000"));SensitiveFields.redact(b);assertFalse(b.containsKey("_secure_bank"));assertThrows(BusinessException.class,()->SensitiveFields.seal(map("bank","6222000000000000"),""));}
 @Test void reviewsRequireACompletedPurchaseAndRefundReclaimsReviewPoints(){String order=create(1);Map<String,Object>b=map("orderId",order,"skuId","stapler","rating",5,"content","真实购买评价","anonymous",true);assertThrows(BusinessException.class,()->workflow.save(customer,2,"review",b,false));complete(order);String ref=str(workflow.save(customer,2,"review",b,false),"id");assertThrows(BusinessException.class,()->workflow.save(customer,2,"review",b,false));workflow.review(owner,ref,map("decision","APPROVED"));assertEquals("匿名用户",workflow.reviews(2,"stapler").get(0).get("name"));assertEquals(2510,db.jdbc().queryForObject("select available from hx_points where member_id=201 and shop_id=0",Long.class));long line=db.jdbc().queryForObject("select id from hx_order_line where order_id=?",Long.class,order);String refund=str(finance.refundApply(customer,order,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","售后退款")),"id");finance.refundReview(owner,refund,map("approve",true));finance.refundConfirmed(refund);assertEquals(2480,db.jdbc().queryForObject("select available from hx_points where member_id=201 and shop_id=0",Long.class));}

 @Test void stocktakeMustReviewUnchangedInventoryAndCannotApplyTwice(){
  String id=str(workflow.save(owner,2,"stocktake",map("items",Collections.singletonList(map("skuId","stapler","actual",318))),true),"id");
  assertEquals(320,number(db.one("select * from hx_sku where shop_id=2 and id='stapler'"),"available"));
  assertThrows(BusinessException.class,()->workflow.review(owner,id,map("decision","APPROVED")));
  workflow.review(owner,id,map("decision","APPROVED","reason","破损两件"));
  assertEquals(318,number(db.one("select * from hx_sku where shop_id=2 and id='stapler'"),"available"));
  assertThrows(BusinessException.class,()->workflow.review(owner,id,map("decision","APPROVED","reason","重复审核")));
  String stale=str(workflow.save(owner,2,"stocktake",map("items",Collections.singletonList(map("skuId","stapler","actual",317))),true),"id");create(1);
  assertThrows(BusinessException.class,()->workflow.review(owner,stale,map("decision","APPROVED","reason","过期盘点")));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_inventory_log where reason='STOCKTAKE'",Integer.class));
 }
 @Test void stocktakeRejectsMissingActualAndStaleDisplayedInventory(){
  Map<String,Object> sku=db.one("select * from hx_sku where shop_id=2 and id='stapler'");
  long available=number(sku,"available"),locked=number(sku,"locked");
  assertThrows(BusinessException.class,()->workflow.save(owner,2,"stocktake",map("items",List.of(map("skuId","stapler"))),true));
  assertThrows(BusinessException.class,()->workflow.save(owner,2,"stocktake",map("items",List.of("stapler")),true));
  assertThrows(BusinessException.class,()->workflow.save(owner,2,"stocktake",map("items",List.of(map("skuId","stapler","actual",available+locked,"expectedAvailable",available-1,"expectedLocked",locked))),true));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='stocktake'",Integer.class));
  assertEquals(available,number(db.one("select * from hx_sku where shop_id=2 and id='stapler'"),"available"));
  Map<String,Object> accepted=workflow.save(owner,2,"stocktake",map("items",List.of(map("skuId","stapler","actual",available+locked,"expectedAvailable",available,"expectedLocked",locked))),true);
  assertEquals("PENDING",accepted.get("status"));
 }
 @Test void closureBlocksOpenBusinessAndRevokesSessionsOnlyAfterConfirmation(){
  assertFalse(bool(accounts.closure(owner),"eligible"));String id=create(1);assertFalse(bool(accounts.closure(customer),"eligible"));orders.cancel(customer,id);
  assertTrue(bool(accounts.closure(customer),"eligible"));assertThrows(BusinessException.class,()->accounts.close(customer,map("confirm",true)));
  accounts.close(customer,map("confirm",true,"forfeitPoints",true));
  assertEquals("CLOSED",db.one("select * from hx_member where id=201").get("status"));assertThrows(BusinessException.class,()->db.member(customer));
  assertEquals(0,db.jdbc().queryForObject("select sum(available) from hx_points where member_id=201",Long.class));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_order where id=?",Integer.class,id));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where member_id=201 and kind='address' and status='ACTIVE'",Integer.class));
 }

 @Test void activeAfterSalesHoldsWithdrawableEarningsAndReleasesOnRejection(){
  String id=create(1);complete(id);long line=number(db.one("select * from hx_order_line where order_id=?",id),"id");
  String ref=str(finance.refundApply(customer,id,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","申请售后")),"id");
  assertEquals(0,wallet(104));assertEquals(1000,number(db.one("select * from hx_wallet where shop_id=2 and member_id=104"),"frozen"));
  assertThrows(BusinessException.class,()->finance.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104")));
  finance.refundReview(owner,ref,map("approve",false,"reason","经协商撤销售后"));assertEquals(1000,wallet(104));assertEquals(0,number(db.one("select * from hx_wallet where shop_id=2 and member_id=104"),"frozen"));
 }

 @Test void targetedCouponOnlyReducesEligibleLineAndQuoteMatchesOrder(){
  Map<String,Object>coupon=workflow.save(owner,2,"coupon",map("type","PRODUCT","skuIds","stapler","discount",100,"threshold",2000,"quantity",10,"expiresAt",System.currentTimeMillis()+86400000),true);
  String id=str(workflow.claim(customer,2,str(coupon,"id")),"id");Map<String,Object>b=body("stapler",1);b.put("items",Arrays.asList(map("id","stapler","qty",1),map("id","cup","qty",1)));b.put("couponId",id);
  workflow.save(owner,2,"freight",map("name","默认运费","freightCents",600,"freeThreshold",0),true);Map<String,Object>q=orders.quote(customer,2,b),o=orders.create(customer,2,b);assertEquals(number(q,"total"),number(o,"total"));
  assertEquals(1900,number(db.one("select * from hx_order_line where order_id=? and sku_id='stapler'",o.get("id")),"paid"));
  assertEquals(number(db.one("select * from hx_sku where shop_id=2 and id='cup'"),"retail"),number(db.one("select * from hx_order_line where order_id=? and sku_id='cup'",o.get("id")),"paid"));
 }
 @Test void shopPointsDeductAtConfiguredRateAndRefundToOriginalScope(){
  workflow.save(owner,2,"points_rule",map("purchaseRate",1,"deductionPercent",20,"checkinPoints",5,"pointsPerYuan",200),true);
  Map<String,Object>b=body("stapler",1);b.put("points",200);b.put("pointsScope",2);String id=str(orders.create(customer,2,b),"id");assertEquals(1900,number(db.one("select * from hx_order where id=?",id),"total"));complete(id);
  long line=number(db.one("select * from hx_order_line where order_id=?",id),"id");String ref=str(finance.refundApply(customer,id,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","积分退款")),"id");finance.refundReview(owner,ref,map("approve",true));finance.refundConfirmed(ref);
  assertEquals(860,number(db.one("select * from hx_points where shop_id=2 and member_id=201"),"available"));assertEquals(2480,number(db.one("select * from hx_points where shop_id=0 and member_id=201"),"available"));
 }
 @Test void promotionQuotaAndNonStackingAreEnforcedAndCancellationReleasesQuota(){
  assertEquals(Boolean.TRUE,orders.quote(customer,2,body("stapler",1)).get("couponStackable"));
  workflow.save(owner,2,"promotion_rule",map("name","限时折扣","type","LIMITED","rateBps",9500,"skuIds","stapler","quantity",1,"perMember",1,"startsAt",System.currentTimeMillis()-1000,"expiresAt",System.currentTimeMillis()+86400000,"stackCoupon",false),true);
  assertEquals(Boolean.FALSE,orders.quote(customer,2,body("stapler",1)).get("couponStackable"));
  String id=create(1);assertEquals(1900,number(db.one("select * from hx_order where id=?",id),"total"));assertThrows(BusinessException.class,()->create(1));orders.cancel(customer,id);
  String coupon=str(workflow.save(owner,2,"coupon",map("discount",50,"threshold",1000,"quantity",1,"expiresAt",System.currentTimeMillis()+86400000),true),"id");String claim=str(workflow.claim(customer,2,coupon),"id");Map<String,Object>b=body("stapler",1);b.put("couponId",claim);assertThrows(BusinessException.class,()->orders.create(customer,2,b));assertEquals(1900,number(orders.create(customer,2,body("stapler",1)),"total"));
 }

 @Test void quoteExposesCouponStackingRuleWithoutPromotionDetails(){
  workflow.save(owner,2,"promotion_rule",map("name","允许叠券活动","type","FULL_REDUCTION","threshold",1000,"discount",100,"skuIds","stapler","startsAt",System.currentTimeMillis()-1000,"expiresAt",System.currentTimeMillis()+86400000,"stackCoupon",true),true);
  Map<String,Object>quote=orders.quote(customer,2,body("stapler",1));
  assertEquals(Boolean.TRUE,quote.get("couponStackable"));
  assertEquals(100,number(quote,"promotionDiscount"));
  assertFalse(quote.containsKey("promotions"));
 }

 @Test void verifiedChannelInboxKeepsFailuresAndReplaysWithoutDoubleCredit(){
  String id=create(1);Map<String,Object>payment=orders.payment(customer,id);
  Map<String,Object>event=map("type","PAYMENT_SUCCESS","id",payment.get("id"),"merchantNo",payment.get("merchant_no"),"amount",2000,"channelRef","WX-A");
  String key=inbox.accept(event);inbox.apply(key);inbox.apply(inbox.accept(event));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_ledger where category='RECEIPT'",Integer.class));
  assertEquals("APPLIED",db.one("select * from hx_channel_event where id=?",key).get("status"));
  Map<String,Object>duplicate=new LinkedHashMap<>(event);duplicate.put("channelRef","WX-B");String second=inbox.accept(duplicate);assertThrows(BusinessException.class,()->inbox.apply(second));inbox.failed(second,"重复渠道流水待对账");assertEquals("ERROR",db.one("select * from hx_channel_event where id=?",second).get("status"));
  assertThrows(BusinessException.class,()->inbox.authorize(new Actor(401,false,false),key));
  HexuChannelEvents controller=new HexuChannelEvents(new UnconfiguredPaymentGateway(),inbox);assertEquals(401,controller.notify("{}",new HashMap<>()).getStatusCodeValue());assertEquals(2,db.jdbc().queryForObject("select count(*) from hx_channel_event",Integer.class));
 }

 @Test void assessmentUsesCompletedSnapshotsAndPromotionKeepsExistingOrders(){
  Actor c=new Actor(103,false,false);assertFalse(bool(new HexuAssessment(db).evaluate(c.id,2),"eligible"));
  String first=create(1),second=create(1);complete(first);complete(second);String old=create(1);
  workflow.save(owner,2,"assessment_rule",map("periodMonths",3,"salesThreshold",3000,"directCount",1,"repeatRate",100,"operator","AND"),true);
  Map<String,Object>assessment=new HexuAssessment(db).evaluate(c.id,2);assertEquals(4000,number(assessment,"sales"));assertEquals(10000,number(assessment,"repeatBps"));assertTrue(bool(assessment,"eligible"));
  String application=str(workflow.save(c,2,"promotion",map("rank",2),false),"id");workflow.review(owner,application,map("decision","APPROVED"));assertEquals(2,catalog.rank(c,2));
  complete(old);assertEquals(30,number(db.one("select * from hx_earning where order_id=? and agent_id=3 and earning_type='PEER'",old),"amount"));
 }

 @Test void pointExpiryIsIdempotentAndLegacyBalanceIsPreserved(){
  db.awardPoints(0,201,100,"CAMPAIGN","expiry-1",1);db.awardPoints(0,201,50,"CAMPAIGN","expiry-2",2);
  db.jdbc().update("update hx_point_batch set expires_at=? where origin_ref='expiry-1'",new java.sql.Timestamp(System.currentTimeMillis()-1000));
  assertEquals(2530,number(db.pointSnapshot(0,201),"available"));db.pointAccount(0,201);db.pointAccount(0,201);
  assertEquals(2530,number(db.pointAccount(0,201),"available"));assertEquals(50,number(db.pointSnapshot(0,201),"expiring"));assertEquals(100,number(db.pointSnapshot(0,201),"expired"));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_point_log where kind='EXPIRE'",Integer.class));
 }
 @Test void transfersPreserveOriginalExpiryAndExpiredPointsCannotBeSpent(){
  db.awardPoints(0,201,100,"CAMPAIGN","transfer-expiry",1);finance.transfer(customer,0,map("recipientId",202,"amount",100));
  Map<String,Object>received=db.one("select * from hx_point_batch where member_id=202 and origin_kind='TRANSFER_IN'");
  assertEquals(db.one("select expires_at from hx_point_batch where origin_ref='transfer-expiry'").get("expires_at"),received.get("expires_at"));
  db.jdbc().update("update hx_point_batch set expires_at=? where id=?",new java.sql.Timestamp(System.currentTimeMillis()-1000),received.get("id"));
  assertThrows(BusinessException.class,()->finance.transfer(new Actor(202,false,false),0,map("recipientId",201,"amount",101)));
  assertEquals(100,number(db.pointAccount(0,202),"available"));
 }
 @Test void cancellationRestoresOriginalBatchWithoutExtendingExpiredCredit(){
  db.awardPoints(0,201,100,"CAMPAIGN","cancel-expiry",1);Map<String,Object>b=body("stapler",1);b.put("points",100);String id=str(orders.create(customer,2,b),"id");
  db.jdbc().update("update hx_point_batch set expires_at=? where origin_ref='cancel-expiry'",new java.sql.Timestamp(System.currentTimeMillis()-1000));orders.cancel(customer,id);orders.cancel(customer,id);
  assertEquals(2480,number(db.pointAccount(0,201),"available"));assertEquals(100,number(db.pointAccount(0,201),"expired"));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_point_log where kind='RETURN_EXPIRED'",Integer.class));
 }
 @Test void expiredPurchaseRewardIsNotReclaimedAsDebtAgain(){
  String id=create(1);complete(id);db.jdbc().update("update hx_point_batch set expires_at=? where origin_ref=? and origin_kind='ORDER_REWARD'",new java.sql.Timestamp(System.currentTimeMillis()-1000),id);db.pointAccount(0,201);
  long line=number(db.one("select * from hx_order_line where order_id=?",id),"id");String ref=str(finance.refundApply(customer,id,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","积分过期后退款")),"id");finance.refundReview(owner,ref,map("approve",true));finance.refundConfirmed(ref);
  assertEquals(2480,number(db.pointAccount(0,201),"available"));assertEquals(0,number(db.pointAccount(0,201),"pending_reclaim"));
 }

 @Test void completeBundleHasOnePriceAndMissingComponentsDoNotDiscount(){
  workflow.save(owner,2,"bundle",map("name","商品组合","type","BUNDLE","items",Arrays.asList(map("skuId","stapler","qty",1),map("skuId","cup","qty",1)),"price",10000,"startsAt",System.currentTimeMillis()-1000,"expiresAt",System.currentTimeMillis()+86400000,"quantity",10,"perMember",2),true);
  assertEquals(2000,number(orders.quote(customer,2,body("stapler",1)),"total"));
  Map<String,Object>b=body("stapler",1);b.put("items",Arrays.asList(map("id","stapler","qty",2),map("id","cup","qty",1)));Map<String,Object>o=orders.create(customer,2,b);assertEquals(12000,number(o,"total"));assertEquals(900,number(o,"discount"));assertEquals(12000,db.jdbc().queryForObject("select sum(paid) from hx_order_line where order_id=?",Long.class,o.get("id")));
 }
 @Test void giftedGoodsAreStockedAndRefundedUsingAllocatedActualPayment(){
  workflow.save(owner,2,"bundle",map("name","满赠活动","type","GIFT","items",Collections.singletonList(map("skuId","stapler","qty",1)),"threshold",8000,"startsAt",System.currentTimeMillis()-1000,"expiresAt",System.currentTimeMillis()+86400000,"quantity",10),true);
  Map<String,Object>b=body("stapler",1);b.put("items",Arrays.asList(map("id","stapler","qty",1),map("id","cup","qty",1)));Map<String,Object>o=orders.create(customer,2,b);assertEquals(8900,number(o,"total"));String id=str(o,"id");complete(id);
  Map<String,Object>line=db.one("select * from hx_order_line where order_id=? and sku_id='stapler'",id);assertTrue(number(line,"paid")>0&&number(line,"paid")<2000);String refund=str(finance.refundApply(customer,id,map("lineId",line.get("id"),"qty",1,"type","RETURN","reason","赠品退货")),"id");finance.refundReview(owner,refund,map("approve",true));finance.inspect(owner,refund,1);finance.refundConfirmed(refund);assertEquals(number(line,"paid"),number(db.one("select * from hx_refund where id=?",refund),"amount"));
 }
 @Test void marketingPriorityAndExclusionPreventAccidentalStacking(){
  Map<String,Object>r=map("name","满减活动","type","FULL_REDUCTION","threshold",1000,"discount",100,"startsAt",System.currentTimeMillis()-1000,"expiresAt",System.currentTimeMillis()+86400000,"priority",20,"exclusiveGroup","AUTUMN","stackPromotions",true);workflow.save(owner,2,"promotion_rule",r,true);r=new LinkedHashMap<>(r);r.put("priority",1);r.put("discount",200);workflow.save(owner,2,"promotion_rule",r,true);
  assertEquals(1800,number(orders.create(customer,2,body("stapler",1)),"total"));
 }
 @Test void tieredPricingUsesHighestReachedThreshold(){
  workflow.save(owner,2,"promotion_rule",map("name","阶梯价","type","TIER","tiers",Arrays.asList(map("threshold",2000,"rateBps",9500),map("threshold",4000,"rateBps",9000)),"startsAt",System.currentTimeMillis()-1000,"expiresAt",System.currentTimeMillis()+86400000),true);
  assertEquals(1900,number(orders.quote(customer,2,body("stapler",1)),"total"));assertEquals(3600,number(orders.create(customer,2,body("stapler",2)),"total"));
 }

 @Test void agentDiscountIsBorneByOwnerAndUnfundedQuoteIsRejected(){
  String campaign=str(workflow.save(owner,2,"coupon",map("discount",100,"threshold",1000,"quantity",10,"expiresAt",System.currentTimeMillis()+86400000),true),"id");String coupon=str(workflow.claim(c1,2,campaign),"id");Map<String,Object>b=body("stapler",1);b.put("couponId",coupon);String id=str(orders.create(c1,2,b),"id");
  assertEquals(-100,number(db.one("select * from hx_earning where order_id=? and earning_type='PROMOTION_COST'",id),"amount"));
  String big=str(workflow.save(owner,2,"coupon",map("discount",200,"threshold",1000,"quantity",1,"expiresAt",System.currentTimeMillis()+86400000),true),"id");b.put("couponId",str(workflow.claim(c1,2,big),"id"));assertThrows(BusinessException.class,()->orders.quote(c1,2,b));assertThrows(BusinessException.class,()->orders.create(c1,2,b));
 }

 @Test void crossPurchaseIsScopedAndCancelledReservationsAreReleased(){
  Map<String,Object>d=workflow.save(c1,3,"cross_purchase",map("sourceShopId",2,"items",List.of(map("skuId","stapler","qty",2)),"expiresAt",System.currentTimeMillis()+86400000),false);workflow.review(platform,str(d,"id"),map("decision","APPROVED"));
  Map<String,Object>stapler=catalog.products(c1,3,false).stream().filter(p->"stapler".equals(str(p,"id"))).findFirst().orElseThrow();assertEquals(1000,number(stapler,"price"));assertEquals(2,number(stapler,"crossPurchaseRemaining"));
  Map<String,Object>cup=catalog.products(c1,3,false).stream().filter(p->"cup".equals(str(p,"id"))).findFirst().orElseThrow();assertEquals(8900,number(cup,"price"));
  assertThrows(BusinessException.class,()->orders.create(c1,3,body("cup",1)));assertThrows(BusinessException.class,()->orders.quote(c1,3,body("stapler",3)));
  Map<String,Object>o=orders.create(c1,3,body("stapler",2));assertThrows(BusinessException.class,()->orders.create(c1,3,body("stapler",1)));assertFalse(catalog.products(c1,3,false).stream().filter(p->"stapler".equals(str(p,"id"))).findFirst().orElseThrow().containsKey("crossPurchaseRemaining"));orders.cancel(c1,str(o,"id"));assertEquals(2,number(catalog.products(c1,3,false).stream().filter(p->"stapler".equals(str(p,"id"))).findFirst().orElseThrow(),"crossPurchaseRemaining"));Map<String,Object>again=orders.create(c1,3,body("stapler",2));assertEquals(2000,number(again,"total"));assertEquals(str(d,"id"),str(again,"cross_authorization_id"));assertNull(db.optional("select * from hx_binding where shop_id=3 and member_id=?",c1.id));
 }

 @Test void consumedCrossPurchaseStillExposesOnlyItsOwnOrderHistory(){
  String authorization=str(workflow.save(c1,3,"cross_purchase",map("sourceShopId",2,"items",List.of(map("skuId","stapler","qty",2)),"expiresAt",System.currentTimeMillis()+86400000,"singleUse",true),false),"id");
  workflow.review(platform,authorization,map("decision","APPROVED"));
  assertTrue(bool(catalog.access(c1,3),"allowed"));
  String first=str(orders.create(c1,3,body("stapler",2)),"id");
  Map<String,Object> access=catalog.access(c1,3);
  assertFalse(bool(access,"allowed"));assertTrue(bool(access,"crossOrderHistory"));assertEquals(first,str(access,"pendingOrderId"));
  assertThrows(BusinessException.class,()->orders.quote(c1,3,body("stapler",1)));
  assertThrows(BusinessException.class,()->orders.create(c1,3,body("stapler",1)));
  assertFalse(bool(catalog.access(customer,3),"crossOrderHistory"));
  orders.cancel(c1,first);
  assertEquals("",str(catalog.access(c1,3),"pendingOrderId"));
  assertEquals(320,number(db.one("select available from hx_sku where shop_id=3 and id='stapler'"),"available"));
  String second=str(orders.create(c1,3,body("stapler",2)),"id");
  Map<String,Object> payment=orders.payment(c1,second);
  orders.paymentConfirmed(str(payment,"id"),str(payment,"merchant_no"),number(payment,"amount"),"TEST-CROSS-PAID");
  access=catalog.access(c1,3);
  assertFalse(bool(access,"allowed"));assertTrue(bool(access,"crossOrderHistory"));assertEquals("",str(access,"pendingOrderId"));
  assertEquals("PAID",str(orders.detail(c1,second,false),"status"));
  assertThrows(BusinessException.class,()->orders.create(c1,3,body("stapler",1)));
 }

 @Test void revokedCrossPurchaseLosesPrivilegeAndKeepsExistingOrderSnapshot(){
  String authorization=str(workflow.save(c1,3,"cross_purchase",map("sourceShopId",2,"items",List.of(map("skuId","stapler","qty",2)),"expiresAt",System.currentTimeMillis()+86400000),false),"id");
  workflow.review(platform,authorization,map("decision","APPROVED"));
  Map<String,Object> order=orders.create(c1,3,body("stapler",1));
  assertEquals(1000,number(order,"total"));
  assertThrows(BusinessException.class,()->workflow.revokeCrossPurchase(owner,authorization,map("shopId",3,"reason","测试撤销")));
  assertThrows(BusinessException.class,()->workflow.revokeCrossPurchase(platform,authorization,map("shopId",2,"reason","错误商城")));
  assertThrows(BusinessException.class,()->workflow.revokeCrossPurchase(platform,authorization,map("shopId",3,"reason"," ")));
  workflow.revokeCrossPurchase(new Actor(401,false,false),authorization,map("shopId",3,"reason","提前终止授权"));
  assertThrows(BusinessException.class,()->workflow.revokeCrossPurchase(new Actor(401,false,false),authorization,map("shopId",3,"reason","重复撤销")));
  assertEquals("REVOKED",str(db.one("select status from hx_document where id=?",authorization),"status"));
  assertEquals("提前终止授权",str(db.parse(db.one("select body_json from hx_document where id=?",authorization).get("body_json")),"revokeReason"));
  assertEquals(0,catalog.rank(c1,3));
  assertThrows(BusinessException.class,()->orders.quote(c1,3,body("stapler",1)));
  assertThrows(BusinessException.class,()->orders.create(c1,3,body("stapler",1)));
  assertEquals(1000,number(db.one("select total from hx_order where id=?",order.get("id")),"total"));
  assertEquals(authorization,str(db.one("select cross_authorization_id from hx_order where id=?",order.get("id")),"cross_authorization_id"));
  orders.cancel(c1,str(order,"id"));
  assertEquals("REVOKED",str(db.one("select status from hx_document where id=?",authorization),"status"));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='REVOKE_cross_purchase' and reference_id=?",Integer.class,authorization));
  assertEquals(401,number(db.one("select actor_id from hx_audit where action='REVOKE_cross_purchase' and reference_id=?",authorization),"actor_id"));
 }

 @Test void assessmentCalendarWindowsAndWeightedRulesAreDeterministic(){
  java.time.LocalDate today=java.time.LocalDate.of(2026,9,21);assertEquals(java.time.LocalDate.of(2026,7,1),HexuAssessment.window(map("periodKind","QUARTER"),today)[0]);assertEquals(java.time.LocalDate.of(2026,1,1),HexuAssessment.window(map("periodKind","YEAR"),today)[0]);assertThrows(BusinessException.class,()->HexuAssessment.validatePeriod(map("periodKind","CUSTOM","startDate","bad","endDate","2026-10-01")));
  Map<String,Object>c=map("periodMonths",3,"operator","WEIGHTED","salesThreshold",100000,"directCount",0,"repeatRate",10,"salesWeight",20,"peopleWeight",70,"repeatWeight",10,"scoreThreshold",70,"salesEnabled",true,"peopleEnabled",true,"repeatEnabled",true);workflow.save(owner,2,"assessment_rule",c,true);Map<String,Object>a=new HexuAssessment(db).evaluate(104,2);assertTrue(bool(a,"eligible"));assertEquals(7000,number(a,"scoreBps"));c.put("peopleWeight",60);assertThrows(BusinessException.class,()->workflow.save(owner,2,"assessment_rule",c,true));
 }

 @Test void shopRewardConfigurationCannotMintPlatformPointsAndRefundReclaimsBothScopes(){
  long beforePlatform=number(db.pointAccount(0,201),"available"),beforeShop=number(db.pointAccount(2,201),"available");workflow.save(owner,2,"points_rule",map("purchaseRate",7,"checkinPoints",9,"reviewPoints",0,"pointsPerYuan",100,"deductionPercent",20,"expiryDays",30),true);String order=create(1);complete(order);Map<String,Object>o=db.one("select * from hx_order where id=?",order);assertEquals(20,number(o,"points_earned"));assertEquals(140,number(o,"shop_points_earned"));assertEquals(beforePlatform+20,number(db.pointAccount(0,201),"available"));assertEquals(beforeShop+140,number(db.pointAccount(2,201),"available"));Map<String,Object>r=finance.refundApply(customer,order,map("lineId",db.one("select id from hx_order_line where order_id=?",order).get("id"),"qty",1,"type","REFUND_ONLY","reason","测试双范围追回"));finance.refundReview(owner,str(r,"id"),map("approve",true));finance.refundConfirmed(str(r,"id"));assertEquals(beforePlatform,number(db.pointAccount(0,201),"available"));assertEquals(beforeShop,number(db.pointAccount(2,201),"available"));
 }
 @Test void refundBeforeReceiveThenRefundRemainderReclaimsAllIssuedPoints(){
  // 独立 H2 沙盒：两件各 50 元，先退一件、收货发积分，再退余款。
  db.jdbc().update("update hx_sku set retail=5000 where shop_id=2 and id='stapler'");
  workflow.save(owner,2,"points_rule",map("purchaseRate",3,"checkinPoints",0,"reviewPoints",0,"pointsPerYuan",100,"deductionPercent",20,"expiryDays",365),true);
  long platformBefore=number(db.pointAccount(0,customer.id),"available"),shopBefore=number(db.pointAccount(2,customer.id),"available");
  String order=create(2);assertEquals(10000,number(db.one("select * from hx_order where id=?",order),"total"));
  pay(order);orders.ship(owner,order,map("carrier","沙盒物流","tracking","SANDBOXREFUND123"));
  long line=number(db.one("select id from hx_order_line where order_id=?",order),"id");
  String first=str(finance.refundApply(customer,order,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","先退一件")),"id");
  finance.refundReview(owner,first,map("approve",true));finance.refundConfirmed(first);
  assertEquals(5000,number(db.one("select * from hx_order where id=?",order),"refunded"));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_point_log where reference_id=? and kind='REFUND_RECLAIM'",Integer.class,first));
  orders.receive(customer,order);
  Map<String,Object> received=db.one("select * from hx_order where id=?",order);
  assertEquals(50,number(received,"points_earned"));assertEquals(150,number(received,"shop_points_earned"));
  assertEquals(platformBefore+50,number(db.pointAccount(0,customer.id),"available"));
  assertEquals(shopBefore+150,number(db.pointAccount(2,customer.id),"available"));
  assertEquals(50,db.jdbc().queryForObject("select sum(remaining) from hx_point_batch where shop_id=0 and member_id=? and origin_kind='ORDER_REWARD' and origin_ref=?",Long.class,customer.id,order));
  assertEquals(150,db.jdbc().queryForObject("select sum(remaining) from hx_point_batch where shop_id=2 and member_id=? and origin_kind='ORDER_REWARD' and origin_ref=?",Long.class,customer.id,order));
  String second=str(finance.refundApply(customer,order,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","再退余款")),"id");
  finance.refundReview(owner,second,map("approve",true));finance.refundConfirmed(second);finance.refundConfirmed(second);
  assertEquals("REFUNDED",db.one("select status from hx_order where id=?",order).get("status"));
  assertEquals(10000,number(db.one("select * from hx_order where id=?",order),"refunded"));
  assertEquals(platformBefore,number(db.pointAccount(0,customer.id),"available"));
  assertEquals(shopBefore,number(db.pointAccount(2,customer.id),"available"));
  assertEquals(-50,db.jdbc().queryForObject("select sum(amount) from hx_point_log where shop_id=0 and member_id=? and kind='REFUND_RECLAIM' and reference_id=?",Long.class,customer.id,second));
  assertEquals(-150,db.jdbc().queryForObject("select sum(amount) from hx_point_log where shop_id=2 and member_id=? and kind='REFUND_RECLAIM' and reference_id=?",Long.class,customer.id,second));
  assertEquals(0,db.jdbc().queryForObject("select sum(remaining) from hx_point_batch where shop_id=0 and member_id=? and origin_kind='ORDER_REWARD' and origin_ref=?",Long.class,customer.id,order));
  assertEquals(0,db.jdbc().queryForObject("select sum(remaining) from hx_point_batch where shop_id=2 and member_id=? and origin_kind='ORDER_REWARD' and origin_ref=?",Long.class,customer.id,order));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='ORDER_RECEIVE' and reference_id=?",Integer.class,order));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='REFUND_REVIEW' and reference_id=?",Integer.class,second));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='REFUND_POINT_RECLAIM' and reference_id=?",Integer.class,second));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_point_log where shop_id=0 and kind='REFUND_RECLAIM' and reference_id=?",Integer.class,second));
 }

 @Test void inspectedDefectiveReturnsNeverBecomeSellableAndAreNotCountedTwice(){
  String order=create(2);complete(order);long stock=number(db.one("select available from hx_sku where shop_id=2 and id='stapler'"),"available");Map<String,Object>r=finance.refundApply(customer,order,map("lineId",db.one("select id from hx_order_line where order_id=?",order).get("id"),"qty",2,"type","RETURN","reason","一件残次一件完好"));finance.refundReview(owner,str(r,"id"),map("approve",true));finance.inspect(owner,str(r,"id"),1);finance.refundConfirmed(str(r,"id"));finance.refundConfirmed(str(r,"id"));Map<String,Object>sku=db.one("select * from hx_sku where shop_id=2 and id='stapler'");assertEquals(stock+1,number(sku,"available"));assertEquals(1,number(sku,"defective"));assertEquals(1,db.jdbc().queryForObject("select sum(defective_delta) from hx_inventory_log where reference_id=?",Long.class,r.get("id")));
 }
 @Test void wholesaleTransitClearsOnlyWhenDestinationReceives(){
  Map<String,Object>b=body("stapler",48);b.put("destinationShopId",2);Map<String,Object>o=orders.create(owner,900,b);Map<String,Object>p=orders.payment(owner,str(o,"id"));orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),number(p,"amount"),"TRANSIT_TEST");orders.ship(platform,str(o,"id"),map("carrier","联调快递","tracking","TRANSIT123456"));assertEquals(48,number(catalog.products(owner,2,true).stream().filter(x->"stapler".equals(str(x,"id"))).findFirst().orElseThrow(),"in_transit"));orders.receive(owner,str(o,"id"));assertEquals(0,number(catalog.products(owner,2,true).stream().filter(x->"stapler".equals(str(x,"id"))).findFirst().orElseThrow(),"in_transit"));
 }

 @Test void settlementPreviewUsesRealTenantChainAndEnforcesFinancialPermission(){Map<String,Object>result=orders.simulate(owner,2,map("skuId","stapler","qty",1,"agentId",4));List<Map<String,Object>>items=(List<Map<String,Object>>)result.get("items");assertEquals(1400,items.stream().mapToLong(e->number(e,"amount")).sum());assertTrue(items.stream().anyMatch(e->"PEER_COST".equals(str(e,"type"))&&number(e,"amount")==-30));assertThrows(BusinessException.class,()->orders.simulate(customer,2,map("skuId","stapler","qty",1,"agentId",4)));assertThrows(BusinessException.class,()->orders.simulate(owner,2,map("skuId","stapler","qty",1,"agentId",20)));}

 @Test void refundEvidenceMustBelongToApplicant(){String order=create(1);complete(order);long line=number(db.one("select id from hx_order_line where order_id=?",order),"id");db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values('EVIDENCE_TEST',2,202,'AFTER_SALES','image/png',?,?)",new byte[12],new byte[16]);assertThrows(BusinessException.class,()->finance.refundApply(customer,order,map("lineId",line,"qty",1,"type","RETURN","reason","凭证归属测试","uploads",List.of("EVIDENCE_TEST"))));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_refund where order_id=?",Long.class,order));Map<String,Object>r=finance.refundApply(customer,order,map("lineId",line,"qty",1,"type","RETURN","reason","凭证归属测试","description","包装破损"));assertEquals("包装破损",str(db.parse(r.get("evidence_json")),"description"));}

 @Test void warehouseInspectionRejectsMissingGoodsAndRetainsInspectionEvidence(){String order=create(2);complete(order);Map<String,Object>r=finance.refundApply(customer,order,map("lineId",db.one("select id from hx_order_line where order_id=?",order).get("id"),"qty",2,"type","RETURN","reason","验收数量测试"));finance.refundReview(owner,str(r,"id"),map("approve",true));assertThrows(BusinessException.class,()->finance.inspect(owner,str(r,"id"),map("receivedQty",1,"goodQty",1)));Map<String,Object>done=finance.inspect(owner,str(r,"id"),map("receivedQty",2,"goodQty",1,"note","一件完好，一件破损"));assertEquals("APPROVED",str(done,"status"));Map<String,Object>evidence=(Map<String,Object>)db.parse(done.get("evidence_json")).get("inspection");assertEquals(1,number(evidence,"goodQty"));assertEquals(101,number(evidence,"inspector"));}

 Map<String,Object> invitationRule(long first,long third,long cap){return workflow.save(owner,2,"invitation_campaign",map("name","订单邀请测试","enabled",true,"firstOrderReward",first,"thirdOrderReward",third,"rewardCap",cap,"minOrderAmount",100,"startsAt",System.currentTimeMillis()-1000,"expiresAt",System.currentTimeMillis()+86400000),true);}
 Actor invitedBuyer(long id){db.jdbc().update("insert into hx_member(id,name,phone,authorized) values(?,?,?,true)",id,"邀请测试用户","13900000"+id);Actor a=new Actor(id,false,false);catalog.bind(a,2,str(catalog.invite(c1,2),"invite"));return a;}
 Map<String,Object> shippedFor(Actor a,int qty){Map<String,Object>o=orders.create(a,2,body("stapler",qty));Map<String,Object>p=orders.payment(a,str(o,"id"));orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),number(p,"amount"),"INV_"+o.get("id"));orders.ship(owner,str(o,"id"),map("carrier","测试物流","tracking","INVTEST123456"));return o;}
 @Test void invitationOnlyPaysCompletedOrdersAndHonorsMilestonesAndLifetimeCap(){invitationRule(100,200,250);Actor buyer=invitedBuyer(203);Map<String,Object>first=shippedFor(buyer,1);assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_invitation_reward",Long.class));orders.receive(buyer,str(first,"id"));orders.receive(buyer,str(first,"id"));assertEquals(1100,wallet(104));Map<String,Object>second=shippedFor(buyer,1);orders.receive(buyer,str(second,"id"));Map<String,Object>third=shippedFor(buyer,1);orders.receive(buyer,str(third,"id"));assertEquals(250,db.jdbc().queryForObject("select sum(amount) from hx_invitation_reward",Long.class));assertEquals(3250,wallet(104));assertEquals(260,wallet(101));assertEquals(2,db.jdbc().queryForObject("select count(*) from hx_invitation_reward",Long.class));assertEquals(250,number(new HexuInvitationRewards(db).status(c1,2),"earned"));}
 @Test void invitationSnapshotSurvivesRuleChangeAndCannotExceedOwnerMargin(){Map<String,Object>rule=invitationRule(1000,2000,5000);Actor buyer=invitedBuyer(203);Map<String,Object>o=shippedFor(buyer,1);workflow.save(owner,2,"invitation_campaign",map("id",rule.get("id"),"enabled",false,"name","停止新单活动"),true);orders.receive(buyer,str(o,"id"));assertEquals(1170,wallet(104));assertEquals(0,wallet(101));Map<String,Object>r=db.one("select * from hx_invitation_reward where order_id=?",o.get("id"));assertEquals(170,number(r,"amount"));assertEquals(1000,number(db.parse(r.get("snapshot_json")),"firstOrderReward"));}
 @Test void publicBindingDoesNotFabricateInvitationOrAllowLateRebinding(){invitationRule(100,200,5000);db.jdbc().update("insert into hx_member(id,name,authorized) values(203,'公共入口用户',true)");Actor buyer=new Actor(203,false,false);catalog.bind(buyer,2,"");catalog.bind(buyer,2,str(catalog.invite(c1,2),"invite"));Map<String,Object>o=shippedFor(buyer,1);orders.receive(buyer,str(o,"id"));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_referral where member_id=203",Long.class));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_invitation_reward",Long.class));assertEquals(1400,wallet(101));}
 @Test void partialRefundReversesInvitationAndOwnerCostWithoutReactivatingMilestone(){invitationRule(1000,2000,5000);Actor buyer=invitedBuyer(203);Map<String,Object>o=shippedFor(buyer,2);orders.receive(buyer,str(o,"id"));long line=number(db.one("select id from hx_order_line where order_id=?",o.get("id")),"id");for(int i=0;i<2;i++){Map<String,Object>r=finance.refundApply(buyer,str(o,"id"),map("lineId",line,"qty",1,"type","RETURN","reason","邀请订单部分退款"));finance.refundReview(owner,str(r,"id"),map("approve",true));finance.inspect(owner,str(r,"id"),1);finance.refundConfirmed(str(r,"id"));finance.refundConfirmed(str(r,"id"));assertEquals(i==0?1170:0,wallet(104));assertEquals(0,wallet(101));}assertEquals(0,number(db.one("select qualifying_amount from hx_invitation_order where order_id=?",o.get("id")),"qualifying_amount"));Map<String,Object>next=shippedFor(buyer,1);orders.receive(buyer,str(next,"id"));assertEquals(1000,wallet(104));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_invitation_reward",Long.class));}
 @Test void concurrentInviteesShareTheSameInviterCap() throws Exception {invitationRule(100,200,150);Actor a=invitedBuyer(203),b=invitedBuyer(204);String first=str(shippedFor(a,1),"id"),second=str(shippedFor(b,1),"id");ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?>one=pool.submit(()->orders.receive(a,first)),two=pool.submit(()->orders.receive(b,second));one.get(10,TimeUnit.SECONDS);two.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(150,db.jdbc().queryForObject("select sum(amount) from hx_invitation_reward",Long.class));assertEquals(2150,wallet(104));assertEquals(190,wallet(101));}

 Map<String,Object> groupRule(){return workflow.save(owner,2,"group_campaign",map("name","二人订书机团","enabled",true,"skuId","stapler","price",1800,"size",2,"hours",1,"startsAt",System.currentTimeMillis()-1000,"expiresAt",System.currentTimeMillis()+86400000),true);}
 @Test void disabledGroupDraftStillRequiresAName(){assertThrows(BusinessException.class,()->workflow.save(owner,2,"group_campaign",map("name","  ","enabled",false),true));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where shop_id=2 and kind='group_campaign'",Integer.class));Map<String,Object>draft=workflow.save(owner,2,"group_campaign",map("name","待配置拼团","enabled",false),true);assertEquals("待配置拼团",str(db.parse(draft.get("body_json")),"name"));}
 Map<String,Object> groupOrder(Actor buyer,String campaign,String group){Map<String,Object>b=body("stapler",1);if(group==null)b.put("groupCampaignId",campaign);else b.put("groupId",group);return orders.create(buyer,2,b);}
 void payFor(Actor buyer,String id){Map<String,Object>p=db.optional("select * from hx_payment where order_id=?",id);if(p==null)p=orders.payment(buyer,id);orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),number(p,"amount"),"GROUP-"+id);}
 @Test void groupRequiresPaidMembersBeforeShippingAndPreservesCustomerBinding(){String campaign=str(groupRule(),"id");Map<String,Object>first=groupOrder(customer,campaign,null);String gid=str((Map<String,Object>)first.get("group"),"id"),id=str(first,"id");assertEquals(1800,number(first,"total"));assertEquals(0,number(first,"freight"));payFor(customer,id);assertThrows(BusinessException.class,()->orders.ship(owner,id,map("carrier","测试","tracking","GROUP123456")));assertEquals(0,wallet(104));Actor second=new Actor(202,false,false);Map<String,Object>next=groupOrder(second,campaign,gid);assertThrows(BusinessException.class,()->groupOrder(second,campaign,gid));assertEquals("FORMING",db.one("select status from hx_group_buy where id=?",gid).get("status"));payFor(second,str(next,"id"));payFor(customer,id);assertEquals("FORMED",db.one("select status from hx_group_buy where id=?",gid).get("status"));orders.ship(owner,id,map("carrier","测试","tracking","GROUP123456"));orders.receive(customer,id);assertEquals(800,wallet(104));assertEquals(170,wallet(101));assertEquals(4,db.jdbc().queryForObject("select agent_id from hx_binding where member_id=201 and shop_id=2",Long.class));}
 @Test void groupFailureProducesIdempotentRefundJobsAndCancelsUnpaidSeats(){String campaign=str(groupRule(),"id");Map<String,Object>first=groupOrder(customer,campaign,null);String gid=str((Map<String,Object>)first.get("group"),"id"),id=str(first,"id");payFor(customer,id);Map<String,Object>second=groupOrder(new Actor(202,false,false),campaign,gid);db.jdbc().update("update hx_group_buy set expires_at=? where id=?",new java.sql.Timestamp(System.currentTimeMillis()-1000),gid);assertEquals(1,orders.expireGroups());assertEquals(0,orders.expireGroups());assertEquals("CANCELLED",db.one("select status from hx_order where id=?",second.get("id")).get("status"));Map<String,Object>r=db.one("select * from hx_refund where order_id=?",id);assertEquals(1800,number(r,"amount"));assertEquals("APPROVED",r.get("status"));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_group_refund",Integer.class));finance.refundConfirmed(str(r,"id"));finance.refundConfirmed(str(r,"id"));assertEquals("REFUNDED",orders.detail(customer,id,false).get("status"));assertEquals(320,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));assertEquals(0,wallet(104));assertEquals(0,db.jdbc().queryForObject("select sum(amount-reversed) from hx_earning where order_id=?",Long.class,id));}
 @Test void groupPricingRejectsTamperingAndNewOrdersUseExistingGroupSnapshot(){String cid=str(groupRule(),"id");Map<String,Object>first=groupOrder(customer,cid,null);String gid=str((Map<String,Object>)first.get("group"),"id");Map<String,Object>b=body("stapler",2);b.put("groupCampaignId",cid);assertThrows(BusinessException.class,()->orders.create(customer,2,b));Map<String,Object>coupon=body("stapler",1);coupon.put("groupCampaignId",cid);coupon.put("points",1);assertThrows(BusinessException.class,()->orders.quote(customer,2,coupon));assertThrows(BusinessException.class,()->groupOrder(c1,cid,null));Map<String,Object>old=db.one("select * from hx_document where id=?",cid),changed=db.parse(old.get("body_json"));changed.put("id",cid);changed.put("price",1900);workflow.save(owner,2,"group_campaign",changed,true);assertEquals(1800,number(groupOrder(new Actor(202,false,false),cid,gid),"total"));assertThrows(BusinessException.class,()->new HexuGroupPurchase(db).detail(customer,3,gid));}
 @Test void concurrentGroupReservationsNeverOversellTheLastSeat()throws Exception{String cid=str(groupRule(),"id");Map<String,Object>first=groupOrder(customer,cid,null);String gid=str((Map<String,Object>)first.get("group"),"id");db.jdbc().update("insert into hx_member(id,name,authorized) values(203,'第三位',true)");ExecutorService pool=Executors.newFixedThreadPool(2);try{List<Future<Boolean>>results=new ArrayList<>();for(long member:new long[]{202,203})results.add(pool.submit(()->{try{groupOrder(new Actor(member,false,false),cid,gid);return true;}catch(BusinessException e){return false;}}));int success=0;for(Future<Boolean>f:results)if(f.get())success++;assertEquals(1,success);assertEquals(2,db.jdbc().queryForObject("select count(*) from hx_group_member where group_id=?",Integer.class,gid));}finally{pool.shutdownNow();}}
 @Test void groupRefundSubmissionRetriesWithoutPretendingFundsReturned(){String cid=str(groupRule(),"id");Map<String,Object>first=groupOrder(customer,cid,null);String gid=str((Map<String,Object>)first.get("group"),"id");payFor(customer,str(first,"id"));db.jdbc().update("update hx_group_buy set expires_at=? where id=?",new java.sql.Timestamp(System.currentTimeMillis()-1000),gid);orders.expireGroups();HexuGroupRefundDispatcher dispatcher=new HexuGroupRefundDispatcher(db,new UnconfiguredPaymentGateway(),new DataSourceTransactionManager(ds));dispatcher.tick();Map<String,Object>job=db.one("select * from hx_group_refund");assertEquals("RETRY",job.get("status"));assertTrue(str(job,"last_error").contains("未配置"));assertEquals("APPROVED",db.one("select status from hx_refund where id=?",job.get("refund_id")).get("status"));dispatcher.tick();assertEquals(1,db.jdbc().queryForObject("select attempts from hx_group_refund",Integer.class));}

 Map<String,Object> downgradeRule(int rank,int failures){return workflow.save(owner,2,"downgrade_rule",map("name","季度保级考核","enabled",true,"rank",rank,"failurePeriods",failures,"firstPeriodStart","2026-01-01","periodKind","QUARTER","periodMonths",3,"salesThreshold",1000,"directCount",0,"repeatRate",0,"operator","AND","salesEnabled",true,"peopleEnabled",false,"repeatEnabled",false),true);}
 void veteran(long agent){db.jdbc().update("update hx_agent set approved_at=? where id=?",Timestamp.valueOf("2025-01-01 00:00:00"),agent);}
 @Test void consecutiveClosedPeriodsProposeExactlyOneReviewedDowngrade(){veteran(2);String rule=str(downgradeRule(2,2),"id");new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{assertEquals(1,new HexuDowngrades(db).run(2,java.time.LocalDate.of(2026,5,1)));return null;});assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='downgrade'",Integer.class));new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{assertEquals(1,new HexuDowngrades(db).run(2,java.time.LocalDate.of(2026,7,1)));assertEquals(0,new HexuDowngrades(db).run(2,java.time.LocalDate.of(2026,7,1)));return null;});Map<String,Object>d=db.one("select * from hx_document where kind='downgrade'");assertEquals("PENDING",d.get("status"));assertEquals(2,number(db.agent(102,2),"rank_no"));Actor center=new Actor(102,false,false);Map<String,Object>old=orders.create(center,2,body("stapler",1));assertEquals(800,number(old,"total"));workflow.review(owner,str(d,"id"),map("decision","APPROVED","reason","已核实连续两季度不达标"));assertEquals(1,number(db.agent(102,2),"rank_no"));assertEquals("APPLIED",db.one("select status from hx_document where id=?",d.get("id")).get("status"));assertEquals(1000,number(orders.create(center,2,body("stapler",1)),"total"));assertEquals(800,number(orders.detail(center,str(old,"id"),false),"total"));assertThrows(BusinessException.class,()->workflow.review(owner,str(d,"id"),map("decision","APPROVED","reason","重复审批")));}
 @Test void passingAQuarterResetsConsecutiveFailures(){veteran(2);downgradeRule(2,2);String id=create(1);complete(id);db.jdbc().update("update hx_order set completed_at=? where id=?",Timestamp.valueOf("2026-05-05 00:00:00"),id);new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{new HexuDowngrades(db).run(2,java.time.LocalDate.of(2026,10,1));return null;});assertEquals(Arrays.asList(1,0,1),db.jdbc().queryForList("select failures from hx_assessment_period where agent_id=2 order by period_start",Integer.class));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='downgrade'",Integer.class));}
 @Test void newlyApprovedAgentsAreNotPenalizedForEarlierPeriods(){downgradeRule(2,1);db.jdbc().update("update hx_agent set approved_at=? where id=2",Timestamp.valueOf("2026-05-05 00:00:00"));new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{assertEquals(0,new HexuDowngrades(db).run(2,java.time.LocalDate.of(2026,7,1)));assertEquals(1,new HexuDowngrades(db).run(2,java.time.LocalDate.of(2026,10,1)));return null;});assertEquals("2026-07-01",db.one("select period_start from hx_assessment_period where agent_id=2").get("period_start"));}
 @Test void shopOwnerMustFinishDispositionBeforeBecomingCloudAgent(){veteran(2);downgradeRule(2,1);workflow.runDowngrades(owner,2);Map<String,Object>d=db.one("select * from hx_document where kind='downgrade'");db.jdbc().update("update hx_shop set owner_id=102 where id=2");assertThrows(BusinessException.class,()->workflow.review(platform,str(d,"id"),map("decision","APPROVED","reason","需先停业")));assertEquals(2,number(db.agent(102,2),"rank_no"));assertEquals("PENDING",db.one("select status from hx_document where id=?",d.get("id")).get("status"));db.jdbc().update("update hx_shop set status='CLOSED' where id=2");workflow.review(platform,str(d,"id"),map("decision","APPROVED","reason","商城已完成停业处置"));assertEquals(1,number(db.agent(102,2),"rank_no"));}
 @Test void scheduledDowngradeRechecksEvidenceBeforeApplying(){veteran(2);downgradeRule(2,1);workflow.runDowngrades(owner,2);Map<String,Object>d=db.one("select * from hx_document where kind='downgrade'");String future=new Timestamp(System.currentTimeMillis()+3600000).toString().substring(0,19);workflow.review(owner,str(d,"id"),map("decision","APPROVED","reason","审核后预约生效","effectiveAt",future));assertEquals(2,number(db.agent(102,2),"rank_no"));workflow.applyDowngrade(str(d,"id"));assertEquals(2,number(db.agent(102,2),"rank_no"));Map<String,Object>b=db.parse(db.one("select body_json from hx_document where id=?",d.get("id")).get("body_json"));b.put("effectiveAt",System.currentTimeMillis()-1000);db.jdbc().update("update hx_document set body_json=? where id=?",db.json(b),d.get("id"));workflow.applyDowngrade(str(d,"id"));workflow.applyDowngrade(str(d,"id"));assertEquals(1,number(db.agent(102,2),"rank_no"));}

 Map<String,Object> handover(String operation,Object... fields){Map<String,Object>b=map("operation",operation,"reason","完整交接验证");b.putAll(map(fields));return workflow.save(owner,2,"shop_transfer",b,true);}
 void approveHandover(Map<String,Object>d){workflow.review(platform,str(d,"id"),map("decision","APPROVED","reason","已核对完整交接清单","handoverConfirmed",true));}
 void emptyShopPoints(){db.jdbc().update("update hx_points set available=0,pending_reclaim=0,frozen=false where shop_id=2");}
 void targetShop(){db.jdbc().update("insert into hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,status,settings) values(700,'同拥有者目标商城','安溪','DEALER',101,'TEST_TARGET',true,'ACTIVE','{}')");db.jdbc().update("insert into hx_agent(id,member_id,shop_id,rank_no) values(700,101,700,3)");db.jdbc().update("insert into hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,available) select id,700,name,spec,asset,retail,cloud_price,center_price,owner_price,5 from hx_sku where shop_id=2");}
 @Test void shopTransferRequiresPlatformConfirmationAndResetsPaymentAuthorization(){db.jdbc().update("update hx_agent set rank_no=3 where id=2");long stock=db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Long.class);Map<String,Object>d=handover("TRANSFER","newOwnerAgentId",2);assertEquals("PENDING",d.get("status"));assertThrows(BusinessException.class,()->workflow.review(owner,str(d,"id"),map("decision","APPROVED","reason","自行批准","handoverConfirmed",true)));approveHandover(d);Map<String,Object>s=db.one("select * from hx_shop where id=2");assertEquals(102,number(s,"owner_id"));assertEquals("ONBOARDING",s.get("status"));assertFalse(bool(s,"payment_ready"));assertNull(s.get("merchant_no"));assertEquals(stock,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Long.class));assertEquals(2,db.jdbc().queryForObject("select parent_id from hx_agent where id=1",Long.class));assertNull(db.one("select parent_id from hx_agent where id=2").get("parent_id"));assertThrows(BusinessException.class,()->orders.create(customer,2,body("stapler",1)));assertTrue(catalog.products(customer,2,false).isEmpty());}
 @Test void storeMergeMovesInventoryAndFutureRelationshipsWithoutMovingPastOrders(){emptyShopPoints();targetShop();String old=create(1);orders.cancel(customer,old);db.jdbc().update("update hx_sku set defective=2 where shop_id=2 and id='stapler'");Map<String,Object>d=handover("MERGE","targetShopId",700);approveHandover(d);assertEquals("MERGED",db.one("select status from hx_shop where id=2").get("status"));assertEquals(325,db.jdbc().queryForObject("select available from hx_sku where shop_id=700 and id='stapler'",Long.class));assertEquals(2,db.jdbc().queryForObject("select defective from hx_sku where shop_id=700 and id='stapler'",Long.class));assertEquals(0,db.jdbc().queryForObject("select sum(available+defective) from hx_sku where shop_id=2",Long.class));assertEquals(700,number(db.agent(104,700),"shop_id"));assertEquals(700,db.jdbc().queryForObject("select parent_id from hx_agent where id=2",Long.class));assertEquals(4,db.jdbc().queryForObject("select agent_id from hx_binding where shop_id=700 and member_id=201",Long.class));assertEquals(2,number(orders.detail(customer,old,false),"shop_id"));assertEquals("MIGRATED",db.one("select status from hx_agent where id=1").get("status"));}
 @Test void storeHandoverRejectsChangedManifestsAndConflictingCustomerBindings(){emptyShopPoints();targetShop();Map<String,Object>d=handover("MERGE","targetShopId",700);catalog.stockAdjust(owner,2,map("skuId","stapler","quantity",1,"reason","审核期间库存变化"));assertThrows(BusinessException.class,()->approveHandover(d));assertEquals("PENDING",db.one("select status from hx_document where id=?",d.get("id")).get("status"));assertEquals("ACTIVE",db.one("select status from hx_shop where id=2").get("status"));workflow.review(platform,str(d,"id"),map("decision","REJECTED","reason","清单变化需重新提交"));db.jdbc().update("insert into hx_binding(member_id,shop_id,agent_id) values(201,700,700)");Map<String,Object>next=handover("MERGE","targetShopId",700);assertThrows(BusinessException.class,()->approveHandover(next));assertEquals(321,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Long.class));assertEquals(2,number(db.agent(104,2),"shop_id"));}
 @Test void shopClosureRequiresClearedBusinessAndLeavesHistoricalOrdersReadable(){emptyShopPoints();String old=create(1);assertThrows(BusinessException.class,()->handover("CLOSE"));orders.cancel(customer,old);assertThrows(BusinessException.class,()->handover("CLOSE"));db.jdbc().update("update hx_sku set available=0,defective=0 where shop_id=2");Map<String,Object>d=handover("CLOSE");approveHandover(d);assertEquals("CLOSED",db.one("select status from hx_shop where id=2").get("status"));assertEquals("CANCELLED",orders.detail(customer,old,false).get("status"));assertTrue(catalog.products(customer,2,false).isEmpty());assertThrows(BusinessException.class,()->catalog.invite(c1,2));}

 @Test void changedScheduledHandoverReturnsToReviewWithoutPartialExecution(){emptyShopPoints();db.jdbc().update("update hx_sku set available=0,defective=0 where shop_id=2");Map<String,Object>d=handover("CLOSE","effectiveAt",new Timestamp(System.currentTimeMillis()+3600000).toString().substring(0,19));approveHandover(d);assertEquals("SCHEDULED",db.one("select status from hx_document where id=?",d.get("id")).get("status"));Map<String,Object>b=db.parse(db.one("select body_json from hx_document where id=?",d.get("id")).get("body_json"));b.put("effectiveTimestamp",System.currentTimeMillis()-1000);db.jdbc().update("update hx_document set body_json=? where id=?",db.json(b),d.get("id"));catalog.stockAdjust(owner,2,map("skuId","stapler","quantity",1,"reason","预约后入库"));assertThrows(BusinessException.class,()->workflow.applyShopTransfer(str(d,"id")));workflow.deferScheduledReview(str(d,"id"),"库存变化，重新复核");workflow.deferScheduledReview(str(d,"id"),"重复检查");assertEquals("REVIEW_REQUIRED",db.one("select status from hx_document where id=?",d.get("id")).get("status"));assertEquals("ACTIVE",db.one("select status from hx_shop where id=2").get("status"));workflow.review(platform,str(d,"id"),map("decision","REJECTED","reason","清单变动，需重新申请"));assertEquals("REJECTED",db.one("select status from hx_document where id=?",d.get("id")).get("status"));}

 Map<String,Object> statementRow(String type,String business,String reference,String merchant,long amount){return map("type",type,"businessId",business,"channelRef",reference,"merchantNo",merchant,"amount",amount);}
 Map<String,Object> statement(Map<String,Object>...rows){return (Map<String,Object>)workflow.importStatement(owner,2,map("statementDate","2026-09-21","rows",Arrays.asList(rows)));}
 void proof(){db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values('FINANCE_PROOF',2,101,'FINANCE','image/png',?,?)",new byte[12],new byte[10]);}
 @Test void statementDoesNotConfirmPaymentAndCanRecheckVerifiedCallback(){String id=create(1);Map<String,Object>p=orders.payment(customer,id),row=statementRow("PAYMENT",str(p,"id"),"CHANNEL-"+id,str(p,"merchant_no"),2000);Map<String,Object>b=statement(row);assertEquals("QUEUED",b.get("status"));assertEquals(b.get("id"),statement(row).get("id"));workflow.processStatement(str(b,"id"));Map<String,Object>line=db.one("select * from hx_reconciliation_line");assertEquals("STATUS_MISMATCH",line.get("status"));assertEquals("UNPAID",orders.detail(customer,id,false).get("status"));pay(id);workflow.recheckStatement(owner,str(line,"id"));assertEquals("MATCHED",db.one("select status from hx_reconciliation_line").get("status"));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_ledger where category='RECON_ADJUST'",Integer.class));}
 @Test void financialAdjustmentNeedsDifferentReviewerAndPreservesOriginalBusiness(){proof();String order=create(1);pay(order);Map<String,Object>p=db.one("select * from hx_payment where order_id=?",order);Map<String,Object>batch=statement(statementRow("PAYMENT",str(p,"id"),str(p,"channel_ref"),str(p,"merchant_no"),1990));workflow.processStatement(str(batch,"id"));Map<String,Object>line=db.one("select * from hx_reconciliation_line"),input=map("reason","渠道尾差凭证已核对","uploads",Arrays.asList("FINANCE_PROOF"));Map<String,Object>a=(Map<String,Object>)workflow.proposeAdjustment(owner,str(line,"id"),input);assertEquals(-10,number(a,"amount"));assertThrows(BusinessException.class,()->workflow.reviewAdjustment(owner,str(a,"id"),map("approve",true,"reason","申请人自行审批")));assertThrows(BusinessException.class,()->workflow.proposeAdjustment(owner,str(line,"id"),input));workflow.reviewAdjustment(platform,str(a,"id"),map("approve",true,"reason","独立复核财务凭证"));assertThrows(BusinessException.class,()->workflow.reviewAdjustment(platform,str(a,"id"),map("approve",true,"reason","重复复核")));assertEquals(-10,db.jdbc().queryForObject("select sum(amount) from hx_ledger where category='RECON_ADJUST'",Long.class));assertEquals(2000,number(db.one("select * from hx_payment where id=?",p.get("id")),"amount"));assertEquals(2000,number(orders.detail(customer,order,false),"total"));assertEquals("RESOLVED",db.one("select status from hx_reconciliation_line").get("status"));}
 @Test void statementScopeAndMerchantMismatchNeverLeakOtherShopAmounts(){String own=create(1);pay(own);Map<String,Object>ownP=db.one("select * from hx_payment where order_id=?",own);Actor other=new Actor(401,false,false);Map<String,Object>foreign=orders.create(other,3,body("stapler",1)),foreignP=orders.payment(other,str(foreign,"id"));Map<String,Object>b=statement(statementRow("PAYMENT",str(foreignP,"id"),"FOREIGN",str(foreignP,"merchant_no"),1),statementRow("PAYMENT",str(ownP,"id"),str(ownP,"channel_ref"),"WRONG_MERCHANT",2000));workflow.processStatement(str(b,"id"));Map<String,Object>foreignLine=db.one("select * from hx_reconciliation_line where business_id=?",foreignP.get("id"));assertEquals("OUT_OF_SCOPE",foreignLine.get("status"));assertNull(foreignLine.get("expected_amount"));assertNull(foreignLine.get("difference"));assertEquals("MERCHANT_MISMATCH",db.one("select status from hx_reconciliation_line where business_id=?",ownP.get("id")).get("status"));assertThrows(BusinessException.class,()->workflow.managementList(owner,3,"reconciliationLines"));}
 @Test void statementProcessingReportsProgressAndRejectsDuplicateRows(){List<Map<String,Object>>rows=new ArrayList<>();for(int i=0;i<105;i++)rows.add(statementRow("PAYMENT","UNKNOWN"+i,"REF"+i,"SANDBOX-ANXI",100));Map<String,Object>b=(Map<String,Object>)workflow.importStatement(owner,2,map("statementDate","2026-09-21","rows",rows));workflow.processStatement(str(b,"id"));Map<String,Object>partial=db.one("select * from hx_reconciliation_batch where id=?",b.get("id"));assertEquals(100,number(partial,"processed_rows"));assertEquals("PROCESSING",partial.get("status"));workflow.processStatement(str(b,"id"));workflow.processStatement(str(b,"id"));assertEquals(105,number(db.one("select * from hx_reconciliation_batch where id=?",b.get("id")),"processed_rows"));assertThrows(BusinessException.class,()->statement(rows.get(0),rows.get(0)));}
 @Test void withdrawalKeepsOriginalMerchantWhenShopPaymentConfigurationChanges(){String order=create(1);complete(order);Map<String,Object>w=finance.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104"));assertEquals("SANDBOX-ANXI",w.get("merchant_no"));finance.reviewWithdrawal(owner,str(w,"id"),true,"");db.jdbc().update("update hx_shop set merchant_no='NEW_MERCHANT' where id=2");String event=inbox.accept(map("type","PAYOUT_SUCCESS","id",w.get("id"),"channelRef","OLD_MERCHANT_PAYOUT","merchantNo","SANDBOX-ANXI","amount",994));inbox.apply(event);assertEquals("PAID",db.one("select status from hx_withdrawal where id=?",w.get("id")).get("status"));}

 @Test void refundReconciliationRequiresActualReceiptReference(){String order=create(1);complete(order);Map<String,Object>r=finance.refundApply(customer,order,map("lineId",db.one("select id from hx_order_line where order_id=?",order).get("id"),"qty",1,"type","REFUND_ONLY","reason","核对退款回执"));finance.refundReview(owner,str(r,"id"),map("approve",true));finance.refundConfirmed(str(r,"id"));Map<String,Object>b=statement(statementRow("REFUND",str(r,"id"),"REAL_REFUND","SANDBOX-ANXI",1990));workflow.processStatement(str(b,"id"));Map<String,Object>line=db.one("select * from hx_reconciliation_line");assertEquals("REFERENCE_MISMATCH",line.get("status"));String event=inbox.accept(map("type","REFUND_SUCCESS","id",r.get("id"),"merchantNo","SANDBOX-ANXI","amount",2000,"channelRef","REAL_REFUND"));inbox.apply(event);workflow.recheckStatement(owner,str(line,"id"));assertEquals(10,number(db.one("select * from hx_reconciliation_line"),"difference"));assertEquals("AMOUNT_MISMATCH",db.one("select status from hx_reconciliation_line").get("status"));}
 @Test void reconciliationFullBatchAndSummaryRespectScopeAndRejectMissingAmounts(){List<Map<String,Object>>rows=new ArrayList<>();for(int i=0;i<501;i++)rows.add(statementRow("PAYMENT","BUS"+i,"CH"+i,"SANDBOX-ANXI",100));Map<String,Object>b=(Map<String,Object>)workflow.importStatement(owner,2,map("statementDate","2026-09-21","rows",rows));assertEquals(501,workflow.statementLines(owner,2,str(b,"id")).size());assertThrows(BusinessException.class,()->workflow.statementLines(owner,3,str(b,"id")));assertThrows(BusinessException.class,()->workflow.managementList(customer,2,"financeSummary"));Map<String,Object>summary=workflow.managementList(owner,2,"financeSummary").get(0);assertEquals(501,number(summary,"statementRows"));Map<String,Object>row=rows.get(0);row.remove("amount");assertThrows(BusinessException.class,()->statement(row));}

 Map<String,Object> linkRule(String mode){proof();db.jdbc().update("update hx_agent set approved_at=?",Timestamp.valueOf("2025-01-01 00:00:00"));return workflow.save(owner,2,"link_campaign",map("name","联动订单验证","enabled",true,"writtenConfirmed",true,"confirmationFile","FINANCE_PROOF","directCount",2,"entryFee",0,"relationshipMode",mode,"passedChild","EARLIEST","startsAt",System.currentTimeMillis()-60000,"expiresAt",System.currentTimeMillis()+86400000,"minOrderAmount",100,"rewardCap",500,"maxRewardPerOrder",1000,"totalRewardBps",10000,"skuIds","stapler","directReward",50,"orderReward",50,"peerReward",50),true);}
 Actor linkAgent(long id,long parent){db.jdbc().update("insert into hx_member(id,name,authorized) values(?,?,true)",id,"联动代理"+id);db.jdbc().update("insert into hx_agent(id,member_id,shop_id,parent_id,rank_no,approved_at) values(?,?,2,?,1,CURRENT_TIMESTAMP)",id,id,parent);Actor a=new Actor(id,false,false);workflow.joinLink(a,2);return a;}
 Map<String,Object> linkedOrder(Actor a){Map<String,Object>o=orders.create(a,2,body("stapler",1)),p=orders.payment(a,str(o,"id"));orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),number(p,"amount"),"LINK-"+o.get("id"));orders.ship(owner,str(o,"id"),map("carrier","测试物流","tracking","LINK12345678"));return o;}
 void linkComplete(Actor a,Map<String,Object>o){orders.receive(a,str(o,"id"));}
 void linkRefund(Actor a,Map<String,Object>o){Map<String,Object>r=finance.refundApply(a,str(o,"id"),map("lineId",db.one("select id from hx_order_line where order_id=?",o.get("id")).get("id"),"qty",1,"type","RETURN","reason","联动订单退款"));finance.refundReview(owner,str(r,"id"),map("approve",true));finance.inspect(owner,str(r,"id"),1);finance.refundConfirmed(str(r,"id"));finance.refundConfirmed(str(r,"id"));}
 @Test void linkCampaignDefaultsOffAndRequiresWrittenCompleteParameters(){assertFalse(bool(new HexuLinkCampaign(db).status(c1,2),"enabled"));assertThrows(BusinessException.class,()->workflow.joinLink(c1,2));assertThrows(BusinessException.class,()->workflow.save(owner,2,"link_campaign",map("enabled",true,"name","缺少完整规则"),true));linkRule("KEEP_RELATION");assertThrows(BusinessException.class,()->workflow.joinLink(customer,2));workflow.joinLink(c1,2);workflow.joinLink(c1,2);assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_link_participant",Long.class));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_link_reward",Long.class));assertFalse(bool(new HexuLinkCampaign(db).status(c1,2),"qualified"));}
 @Test void twoQualifiedNewAgentsPromoteOnlyMarketingRoleAndRefundReverses(){Map<String,Object>rule=linkRule("KEEP_RELATION");workflow.joinLink(c1,2);linkComplete(c1,linkedOrder(c1));Actor a=linkAgent(205,4),b=linkAgent(206,4);Map<String,Object>one=linkedOrder(a),two=linkedOrder(b);assertEquals(0,number(new HexuLinkCampaign(db).status(c1,2),"validDirect"));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_link_reward where order_id=?",Long.class,one.get("id")));linkComplete(a,one);linkComplete(b,two);linkComplete(b,two);Map<String,Object>status=new HexuLinkCampaign(db).status(c1,2);assertEquals(2,number(status,"validDirect"));assertEquals("BOSS",((Map<?,?>)status.get("participant")).get("role"));assertEquals(1,number(db.agent(104,2),"rank_no"));assertEquals(3,number(db.agent(104,2),"parent_id"));assertEquals(2,db.jdbc().queryForObject("select count(*) from hx_link_reward where reward_type='LINK_DIRECT'",Long.class));linkRefund(b,two);assertEquals(1,number(new HexuLinkCampaign(db).status(c1,2),"validDirect"));assertEquals("AGENT",db.one("select role from hx_link_participant where agent_id=4").get("role"));assertEquals(0,db.jdbc().queryForObject("select sum(amount-reversed) from hx_earning where order_id=? and earning_type like 'LINK_%'",Long.class,two.get("id")));linkComplete(b,linkedOrder(b));assertEquals(2,db.jdbc().queryForObject("select count(*) from hx_link_reward where reward_type='LINK_DIRECT'",Long.class));}
 @Test void existingAgentsDoNotCountAsNewAndUnrelatedSkusDoNotQualify(){linkRule("KEEP_RELATION");workflow.joinLink(c1,2);Actor c=new Actor(103,false,false);workflow.joinLink(c,2);linkComplete(c,linkedOrder(c));linkComplete(c1,linkedOrder(c1));assertEquals(0,number(new HexuLinkCampaign(db).status(c,2),"validDirect"));Map<String,Object>o=orders.create(c1,2,body("cup",1)),p=orders.payment(c1,str(o,"id"));orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),number(p,"amount"),"LINK-CUP");orders.ship(owner,str(o,"id"),map("carrier","测试物流","tracking","LINK12345678"));orders.receive(c1,str(o,"id"));assertNull(db.optional("select order_id from hx_link_order where order_id=?",o.get("id")));}
 @Test void linkRewardsHonorMarginTotalAndLifetimeCapAcrossRuleEdits(){Map<String,Object>rule=linkRule("KEEP_RELATION");Map<String,Object>b=db.parse(rule.get("body_json"));b.put("id",rule.get("id"));b.put("orderReward",10000);b.put("rewardCap",200);Map<String,Object>updated=workflow.save(owner,2,"link_campaign",b,true);workflow.joinLink(c1,2);Map<String,Object>first=linkedOrder(c1);linkComplete(c1,first);long firstReward=db.jdbc().queryForObject("select amount from hx_link_reward where order_id=?",Long.class,first.get("id"));assertTrue(firstReward>0&&firstReward<=200);assertTrue(db.jdbc().queryForObject("select sum(amount-reversed) from hx_earning where order_id=? and agent_id=1",Long.class,first.get("id"))>=0);Map<String,Object>next=linkedOrder(c1);linkComplete(c1,next);assertEquals(200,number(new HexuLinkCampaign(db).status(c1,2),"gross"));linkRefund(c1,first);linkComplete(c1,linkedOrder(c1));assertEquals(200,number(new HexuLinkCampaign(db).status(c1,2),"gross"));b=db.parse(updated.get("body_json"));b.put("id",updated.get("id"));b.put("maxRewardPerOrder",1);workflow.save(owner,2,"link_campaign",b,true);Map<String,Object>blocked=linkedOrder(c1);linkComplete(c1,blocked);assertEquals(0,db.jdbc().queryForObject("select amount from hx_link_reward where order_id=?",Long.class,blocked.get("id")));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_link_participant where agent_id=4",Long.class));}
 @Test void campaignDisableDoesNotRewriteAcceptedOrderTerms(){Map<String,Object>rule=linkRule("KEEP_RELATION");workflow.joinLink(c1,2);Map<String,Object>o=linkedOrder(c1);workflow.save(owner,2,"link_campaign",map("id",rule.get("id"),"enabled",false),true);linkComplete(c1,o);assertEquals(50,db.jdbc().queryForObject("select amount from hx_link_reward where order_id=?",Long.class,o.get("id")));Map<String,Object>next=linkedOrder(c1);linkComplete(c1,next);assertNull(db.optional("select order_id from hx_link_order where order_id=?",next.get("id")));}
 @Test void linkRelationshipChangeRequiresReviewAndDoesNotAlterCoreChain(){linkRule("EXIT_PASS_ONE");workflow.joinLink(owner,2);workflow.joinLink(new Actor(102,false,false),2);Actor parent=new Actor(103,false,false);workflow.joinLink(parent,2);workflow.joinLink(c1,2);linkComplete(c1,linkedOrder(c1));Actor a=linkAgent(205,4),b=linkAgent(206,4);linkComplete(a,linkedOrder(a));linkComplete(b,linkedOrder(b));Map<String,Object>d=db.one("select * from hx_document where kind='link_relation'");assertEquals("PENDING",d.get("status"));assertEquals(3,number(db.one("select * from hx_link_participant where agent_id=4"),"marketing_parent_id"));assertThrows(BusinessException.class,()->workflow.review(customer,str(d,"id"),map("decision","APPROVED")));workflow.review(owner,str(d,"id"),map("decision","APPROVED","reason","已核对营销关系处置"));assertEquals(2,number(db.one("select * from hx_link_participant where agent_id=4"),"marketing_parent_id"));assertEquals(3,number(db.one("select * from hx_link_participant where agent_id=205"),"marketing_parent_id"));assertEquals(3,number(db.agent(104,2),"parent_id"));assertEquals(4,number(db.agent(205,2),"parent_id"));}

 @Test void secondLevelPeerRewardNeverTraversesFurtherAncestors(){linkRule("KEEP_RELATION");Actor c=new Actor(103,false,false);workflow.joinLink(c,2);workflow.joinLink(c1,2);linkComplete(c,linkedOrder(c));linkComplete(c1,linkedOrder(c1));Actor buyer=linkAgent(205,4);linkComplete(buyer,linkedOrder(buyer));db.jdbc().update("update hx_link_participant set role='BOSS' where agent_id in (3,205)");Map<String,Object>o=linkedOrder(buyer);linkComplete(buyer,o);assertEquals(3,db.jdbc().queryForObject("select recipient_agent_id from hx_link_reward where order_id=? and reward_type='LINK_PEER'",Long.class,o.get("id")));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_link_reward where order_id=? and recipient_agent_id in (1,2)",Long.class,o.get("id")));assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_link_reward where order_id=? and reward_type='LINK_PEER'",Long.class,o.get("id")));}
 @Test void concurrentLinkOrderCompletionSharesOneLifetimeCap() throws Exception {Map<String,Object>rule=linkRule("KEEP_RELATION"),b=db.parse(rule.get("body_json"));b.put("id",rule.get("id"));b.put("rewardCap",75);workflow.save(owner,2,"link_campaign",b,true);workflow.joinLink(c1,2);Map<String,Object>one=linkedOrder(c1),two=linkedOrder(c1);ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?>a=pool.submit(()->linkComplete(c1,one)),c=pool.submit(()->linkComplete(c1,two));a.get(10,TimeUnit.SECONDS);c.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(75,number(new HexuLinkCampaign(db).status(c1,2),"gross"));}

 @Test void earningDetailUsesOwnHistoricalPriceAndNeverExposesOtherAccountSnapshot(){String order=create(2);complete(order);long id=db.jdbc().queryForObject("select id from hx_earning where order_id=? and agent_id=4",Long.class,order);db.jdbc().update("update hx_sku set cloud_price=1200 where shop_id=2 and id='stapler'");db.jdbc().update("update hx_agent set rank_no=2 where id=4");Map<String,Object>d=new HexuReadModels(db).earning(c1,id);assertEquals(1000,number(d,"settlementPrice"));assertFalse(((Map<?,?>)d.get("line")).containsKey("snapshot_json"));assertThrows(BusinessException.class,()->new HexuReadModels(db).earning(customer,id));assertThrows(BusinessException.class,()->new HexuReadModels(db).earning(new Actor(103,false,false),id));}
 @Test void withdrawalReadModelAndChannelMustMatchApprovedAccount(){String order=create(1);complete(order);assertThrows(BusinessException.class,()->finance.withdraw(c1,2,map("amount",1000,"channel","WECHAT","accountRef","BANK104")));assertEquals(1000,wallet(104));Map<String,Object>w=finance.withdraw(c1,2,map("amount",1000,"channel","BANK","accountRef","BANK104"));Map<String,Object>detail=new HexuReadModels(db).withdrawal(c1,str(w,"id"),false);assertEquals("工商银行（3287）",detail.get("accountLabel"));assertEquals(994,number(detail,"net"));assertThrows(BusinessException.class,()->new HexuReadModels(db).withdrawal(customer,str(w,"id"),false));assertThrows(BusinessException.class,()->new HexuReadModels(db).withdrawal(customer,str(w,"id"),true));assertEquals(1000,number(new HexuReadModels(db).withdrawal(owner,str(w,"id"),true),"amount"));}
 @Test void balanceWithdrawalDetailShowsReviewedAccountName(){String order=create(1);complete(order);String account=str(workflow.save(c1,2,"settlement_account",map("channel","BALANCE","accountName","测试代理"),false),"id");workflow.review(owner,account,map("decision","APPROVED"));String withdrawal=str(finance.withdraw(c1,2,map("amount",100,"channel","BALANCE","accountRef",account)),"id");assertEquals("系统余额 · 测试代理",new HexuReadModels(db).withdrawal(c1,withdrawal,false).get("accountLabel"));}
 @Test void managementDirectoryMasksPhonesAndReversalIsTenantScoped(){String order=create(1);complete(order);List<Map<String,Object>>customers=new HexuReadModels(db).customers(owner,2);assertTrue(customers.stream().allMatch(r->!str(r,"phone").matches("[0-9]{11}")));assertThrows(BusinessException.class,()->new HexuReadModels(db).agents(customer,2));assertThrows(BusinessException.class,()->new HexuReadModels(db).customers(owner,3));assertThrows(BusinessException.class,()->new HexuReadModels(db).reversal(owner,3,order));assertFalse(((List<?>)new HexuReadModels(db).reversal(owner,2,order).get("earnings")).isEmpty());}

 @Test void crossPurchaseReviewerMayNarrowButCannotExpandRequestedScope(){long expires=System.currentTimeMillis()+86400000;Map<String,Object>d=workflow.save(c1,3,"cross_purchase",map("sourceShopId",2,"items",List.of(map("skuId","stapler","qty",2)),"expiresAt",expires),false);assertThrows(BusinessException.class,()->workflow.review(platform,str(d,"id"),map("decision","APPROVED","approvedItems",List.of(map("skuId","stapler","qty",3)))));assertThrows(BusinessException.class,()->workflow.review(platform,str(d,"id"),map("decision","APPROVED","approvedExpiresAt",expires+86400000)));workflow.review(platform,str(d,"id"),map("decision","APPROVED","approvedItems",List.of(map("skuId","stapler","qty",1)),"approvedExpiresAt",expires-1000));assertThrows(BusinessException.class,()->orders.create(c1,3,body("stapler",2)));assertEquals(1000,number(orders.create(c1,3,body("stapler",1)),"total"));Map<String,Object>b=db.parse(db.one("select body_json from hx_document where id=?",d.get("id")).get("body_json"));assertEquals(2,number(((List<Map<String,Object>>)b.get("requestedItems")).get(0),"qty"));}

 @Test void skuMetadataAndCategoryRemainScopedAndRedactPriceBounds(){workflow.save(owner,2,"category",map("items",List.of("办公用品","生活日用")),true);Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=2 and id='stapler'"));p.putAll(map("category","办公用品","product_group","stationery","description","商品详情来自数据库","weight_grams",350));catalog.saveSku(owner,2,p);Map<String,Object>visible=catalog.products(customer,2,false).stream().filter(x->"stapler".equals(x.get("id"))).findFirst().orElseThrow();assertEquals("办公用品",visible.get("category"));assertEquals("商品详情来自数据库",visible.get("description"));assertFalse(visible.containsKey("cloud_price"));assertFalse(visible.containsKey("retail_max"));p.put("category","不存在的分类");assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p));assertThrows(BusinessException.class,()->workflow.save(owner,2,"category",map("items",List.of("重复","重复")),true));}
 @Test void catalogStockEditCannotOverwriteConcurrentOrderLock(){Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=2 and id='stapler'"));p.putAll(map("expectedAvailable",320,"available",330,"stockReason","补货修正"));String order=create(1);assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p));assertEquals(319,number(db.one("select available from hx_sku where shop_id=2 and id='stapler'"),"available"));p.put("expectedAvailable",319);catalog.saveSku(owner,2,p);assertEquals(1,number(db.one("select locked from hx_sku where shop_id=2 and id='stapler'"),"locked"));assertEquals(11,number(db.one("select available_delta from hx_inventory_log where shop_id=2 and reason='ADJUST'"),"available_delta"));orders.cancel(customer,order);assertEquals(331,number(db.one("select available from hx_sku where shop_id=2 and id='stapler'"),"available"));}
 @Test void hqRetailBoundsAndOwnedProductReviewAreEnforced(){Map<String,Object>standard=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=900 and id='stapler'"));standard.putAll(map("retail_min",1500,"retail_max",2500));catalog.saveSku(platform,900,standard);Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=2 and id='stapler'"));p.put("retail",2600);assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p));p.put("retail",2000);p.put("id","custom-sku");p.remove("available");db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values('FILE_TEST_PRODUCT',2,101,'PRODUCT','image/png',?,?)",new byte[12],new byte[]{1});p.put("asset","FILE_TEST_PRODUCT");p.put("gallery",List.of("FILE_TEST_PRODUCT"));catalog.saveSku(owner,2,p);assertEquals("PENDING",db.one("select status from hx_sku where shop_id=2 and id='custom-sku'").get("status"));assertThrows(BusinessException.class,()->catalog.status(owner,2,map("id","custom-sku","status","ACTIVE")));catalog.status(platform,2,map("id","custom-sku","status","ACTIVE"));p.put("name","修改后须重新审核");catalog.saveSku(owner,2,p);assertEquals("PENDING",db.one("select status from hx_sku where shop_id=2 and id='custom-sku'").get("status"));}
 @Test void newProductNeedsUploadedMainImage(){Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=2 and id='stapler'"));p.put("id","new-without-image");p.remove("available");assertEquals("请先上传商品主图",assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p)).getMessage());assertNull(db.optional("select id from hx_sku where shop_id=2 and id='new-without-image'"));p.put("asset","FILE_NOT_UPLOADED");p.put("gallery",List.of("FILE_NOT_UPLOADED"));assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p));assertNull(db.optional("select id from hx_sku where shop_id=2 and id='new-without-image'"));}
 @Test void privateIdentityAttachmentCannotBecomeProductImage(){db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values('IDENTITY_PRIVATE',2,101,'IDENTITY','image/png',?,?)",new byte[12],new byte[]{1});Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=2 and id='stapler'"));p.put("gallery",List.of("IDENTITY_PRIVATE"));assertThrows(BusinessException.class,()->catalog.saveSku(owner,2,p));assertNull(db.optional("select * from hx_sku_detail where shop_id=2 and sku_id='stapler'"));}
 @Test void nonLooseSkuEnforcesSameBoxMinimumInQuoteAndOrder(){catalog.boxRules(owner,2,map("skuId","stapler","box_size",6,"min_boxes",2,"min_qty",1,"mix_units",1,"mix_group","OFFICE","allow_loose",false));assertThrows(BusinessException.class,()->orders.quote(customer,2,body("stapler",6)));assertThrows(BusinessException.class,()->orders.create(customer,2,body("stapler",13)));assertEquals(24000,number(orders.quote(customer,2,body("stapler",12)),"total"));String id=str(orders.create(customer,2,body("stapler",12)),"id");assertEquals(6,number(db.parse(db.one("select snapshot_json from hx_order_line where order_id=?",id).get("snapshot_json")),"boxSize"));}
 @Test void mixedBoxConfigurationActuallyDrivesWholesaleValidation(){Map<String,Object>b=map("skuId","stapler","box_size",24,"min_boxes",2,"min_qty",1,"mix_units",2,"mix_group","SAME","allow_loose",true,"mixedEnabled",true,"mixCapacity",24,"minMixBoxes",2);assertThrows(BusinessException.class,()->catalog.boxRules(owner,900,b));catalog.boxRules(platform,900,b);b.put("skuId","cup");b.put("mix_units",1);catalog.boxRules(platform,900,b);Map<String,Object>o=map("items",List.of(map("id","stapler","qty",12),map("id","cup","qty",24)),"destinationShopId",2,"address",map("name","采购人","detail","测试仓"));assertNotNull(orders.create(owner,900,o));o.put("items",List.of(map("id","stapler","qty",11),map("id","cup","qty",24)));assertThrows(BusinessException.class,()->orders.create(owner,900,o));}
 @Test void freightPieceThresholdAndRemoteFeesUseActualDestination(){String id=str(workflow.save(owner,2,"freight",map("name","偏远运费","billing","COUNT","firstUnits",1,"stepUnits",1,"freightCents",500,"extraFee",200,"freeThreshold",6000,"remoteFee",1000,"remoteRegions","新疆,西藏"),true),"id");Map<String,Object>b=body("stapler",2);((Map<String,Object>)b.get("address")).put("region","福建省 泉州市");assertEquals(700,number(orders.quote(customer,2,b),"freight"));((Map<String,Object>)b.get("address")).put("region","新疆 乌鲁木齐");assertEquals(1700,number(orders.quote(customer,2,b),"freight"));assertEquals(1700,number(orders.create(customer,2,b),"freight"));b=body("stapler",3);((Map<String,Object>)b.get("address")).put("region","新疆 乌鲁木齐");assertEquals(1000,number(orders.quote(customer,2,b),"freight"));b.remove("address");Map<String,Object>missing=b;assertThrows(BusinessException.class,()->orders.quote(customer,2,missing));}
 @Test void weightFreightGroupsSkusPerTemplateAndKeepsOrderSnapshot(){String ref=str(workflow.save(owner,2,"freight",map("name","重量运费","billing","WEIGHT","firstUnits",1000,"stepUnits",500,"freightCents",600,"extraFee",200),true),"id");for(String sku:List.of("stapler","cup")){Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=2 and id=?",sku));p.putAll(map("weight_grams",800,"freight_id",ref));catalog.saveSku(owner,2,p);}Map<String,Object>b=body("stapler",1);b.put("items",List.of(map("id","stapler","qty",1),map("id","cup","qty",1)));assertEquals(1000,number(orders.quote(customer,2,b),"freight"));String order=str(orders.create(customer,2,b),"id");workflow.save(owner,2,"freight",map("id",ref,"name","重量运费","freightCents",10),true);assertEquals(1000,number(db.one("select freight from hx_order where id=?",order),"freight"));Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=3 and id='stapler'"));p.put("freight_id",ref);assertThrows(BusinessException.class,()->catalog.saveSku(platform,3,p));}


 @Test void catalogSettingsSaveRollsBackCategoriesWhenFreightCannotBeSaved(){String id=str(workflow.save(owner,2,"category",map("items",List.of("原分类")),true),"id");assertThrows(BusinessException.class,()->workflow.saveCatalogSettings(owner,2,map("categoryId",id,"categories",List.of("新分类"),"freight",map("id","FOREIGN_TEMPLATE","name","有效模板","freightCents",100))));assertEquals(List.of("原分类"),db.config(2,"category").get("items"));}
 @Test void catalogSettingsCannotCreateUnnamedFreightTemplate(){
  String id=str(workflow.save(owner,2,"category",map("items",List.of("原分类")),true),"id");
  for(String name:List.of(""," ","A\nB")){
   Map<String,Object> freight=map("name",name,"billing","FLAT","freightCents",0);
   assertThrows(BusinessException.class,()->workflow.saveCatalogSettings(owner,2,map("categoryId",id,"categories",List.of("新分类"),"freight",freight)));
   assertThrows(BusinessException.class,()->workflow.save(owner,2,"freight",freight,true));
  }
  assertEquals(List.of("原分类"),db.config(2,"category").get("items"));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where shop_id=2 and kind='freight'",Integer.class));
 }

 @Test void marketingPageShowsBuyerQuotaFromCommittedOrders(){
  workflow.save(owner,2,"promotion_rule",map("name","库存展示测试","type","LIMITED","rateBps",9500,"skuIds","stapler","quantity",2,"perMember",1,"startsAt",System.currentTimeMillis()-1000,"expiresAt",System.currentTimeMillis()+86400000),true);
  Map<String,Object>before=workflow.marketing(customer,2).get(0);
  assertEquals(2,number(before,"remaining"));assertEquals(1,number(before,"memberRemaining"));assertTrue(bool(before,"available"));
  assertEquals(2,number((Map<String,Object>)before.get("body"),"quantity"));
  String order=create(1);
  Map<String,Object>after=workflow.marketing(customer,2).get(0);
  assertEquals(1,number(after,"remaining"));assertEquals(0,number(after,"memberRemaining"));assertFalse(bool(after,"available"));
  orders.cancel(customer,order);
  Map<String,Object>restored=workflow.marketing(customer,2).get(0);
  assertEquals(2,number(restored,"remaining"));assertEquals(1,number(restored,"memberRemaining"));assertTrue(bool(restored,"available"));
 }
 @Test void parallelAccountReadsPreservePointBalances() throws Exception {
  finance.account(customer,2); // Complete any legacy batch initialization before the race.
  long platform=number(db.pointSnapshot(0,customer.id),"available"),shop=number(db.pointSnapshot(2,customer.id),"available");
  long logs=db.jdbc().queryForObject("select count(*) from hx_point_log where member_id=?",Long.class,customer.id);
  ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
  try {
   List<Future<Map<String,Object>>> reads=new ArrayList<>();
   for(int i=0;i<8;i++)reads.add(pool.submit(()->{start.await();return finance.account(customer,2);}));
   start.countDown();
   for(Future<Map<String,Object>> read:reads){Map<String,Object> result=read.get(10,TimeUnit.SECONDS);assertEquals(platform,number((Map<String,Object>)result.get("platformPoints"),"available"));assertEquals(shop,number((Map<String,Object>)result.get("shopPoints"),"available"));}
  } finally {pool.shutdownNow();}
  assertEquals(logs,db.jdbc().queryForObject("select count(*) from hx_point_log where member_id=?",Long.class,customer.id));
 }
 @Test void parallelWalletEnsuresPreserveExistingBalance() throws Exception {
  db.ensureWallet(2,customer.id);
  db.jdbc().update("update hx_wallet set available=123,frozen=45 where shop_id=2 and member_id=?",customer.id);
  ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
  try {
   List<Future<?>> ensures=new ArrayList<>();
   for(int i=0;i<8;i++)ensures.add(pool.submit(()->{start.await();db.ensureWallet(2,customer.id);return null;}));
   start.countDown();
   for(Future<?> ensure:ensures)ensure.get(10,TimeUnit.SECONDS);
  } finally {pool.shutdownNow();}
  Map<String,Object> wallet=db.one("select * from hx_wallet where shop_id=2 and member_id=?",customer.id);
  assertEquals(123,number(wallet,"available"));assertEquals(45,number(wallet,"frozen"));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_wallet where shop_id=2 and member_id=?",Integer.class,customer.id));
 }
 @Test void categoryOnlyUpdateDoesNotCreateEmptyFreightTemplate(){
  String id=str(workflow.save(owner,2,"category",map("items",List.of("原分类")),true),"id");
  Map<String,Object> saved=workflow.saveCatalogSettings(owner,2,map("categoryId",id,"categories",List.of("生活日用","办公用品")));
  assertFalse(saved.containsKey("freight"));
  assertEquals(List.of("生活日用","办公用品"),db.config(2,"category").get("items"));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where shop_id=2 and kind='freight'",Integer.class));
 }
 @Test void skuCannotPublishPriceThatCannotCoverCurrentPeerCost(){Map<String,Object>p=new LinkedHashMap<>(db.one("select * from hx_sku where shop_id=2 and id='stapler'"));p.putAll(map("cloud_price",1000,"center_price",1000,"owner_price",1000));assertThrows(BusinessException.class,()->catalog.saveSku(platform,2,p));assertEquals(600,number(db.one("select owner_price from hx_sku where shop_id=2 and id='stapler'"),"owner_price"));}

 @Test void frequentPointTransfersFreezeAtomicallyWithoutThirdDebit(){workflow.save(platform,0,"points_risk",map("singleLimit",1000,"dailyLimit",5000,"monthlyLimit",30000,"frequencyEnabled",true,"dailyCount",10,"windowCount",2,"windowSeconds",60,"autoFreeze",true),true);finance.transfer(customer,0,map("recipientId",202,"amount",1));finance.transfer(customer,0,map("recipientId",202,"amount",1));Map<String,Object>blocked=finance.transfer(customer,0,map("recipientId",202,"amount",1));assertTrue(bool(blocked,"blocked"));assertEquals(2478,number(db.pointAccount(0,201),"available"));assertTrue(bool(db.pointAccount(0,201),"frozen"));assertEquals(2,db.jdbc().queryForObject("select count(*) from hx_point_log where member_id=201 and kind='TRANSFER_OUT'",Integer.class));assertThrows(BusinessException.class,()->workflow.reviewPointFreeze(owner,str(blocked,"caseId"),map("release",true,"reason","越权解冻")));workflow.reviewPointFreeze(platform,str(blocked,"caseId"),map("release",true,"reason","平台核实为本人正常操作"));assertFalse(bool(db.pointAccount(0,201),"frozen"));assertThrows(BusinessException.class,()->workflow.reviewPointFreeze(platform,str(blocked,"caseId"),map("release",true,"reason","重复复核")));}
 @Test void frequencyBlockReplaysWithoutDuplicatingFreezeCase(){workflow.save(platform,0,"points_risk",map("singleLimit",1000,"dailyLimit",5000,"monthlyLimit",30000,"frequencyEnabled",true,"dailyCount",1,"windowCount",1,"windowSeconds",60,"autoFreeze",true),true);finance.transfer(customer,0,map("recipientId",202,"amount",1));Map<String,Object>b=map("recipientId",202,"amount",1);Object first=commands.execute(customer,"risk-replay-01","points-transfer",b,()->finance.transfer(customer,0,b));Object replay=commands.execute(customer,"risk-replay-01","points-transfer",b,()->{fail("replay must not rerun");return null;});assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_point_risk_case",Integer.class));assertTrue(bool((Map<String,Object>)replay,"blocked"));}
 @Test void concurrentTransferFrequencyLimitHasOnlyOneSuccessfulDebit()throws Exception{workflow.save(platform,0,"points_risk",map("singleLimit",1000,"dailyLimit",5000,"monthlyLimit",30000,"frequencyEnabled",true,"dailyCount",1,"windowCount",1,"windowSeconds",60,"autoFreeze",true),true);ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<Map<String,Object>>a=pool.submit(()->finance.transfer(customer,0,map("recipientId",202,"amount",1))),b=pool.submit(()->finance.transfer(customer,0,map("recipientId",202,"amount",1)));assertEquals(1,(bool(a.get(10,TimeUnit.SECONDS),"blocked")?1:0)+(bool(b.get(10,TimeUnit.SECONDS),"blocked")?1:0));assertEquals(2479,number(db.pointAccount(0,201),"available"));}finally{pool.shutdownNow();}}
 @Test void disabledAutoFreezeRejectsFrequencyWithoutFreezingOrMovingPoints(){workflow.save(platform,0,"points_risk",map("singleLimit",1000,"dailyLimit",5000,"monthlyLimit",30000,"frequencyEnabled",true,"dailyCount",1,"windowCount",1,"windowSeconds",60,"autoFreeze",false),true);finance.transfer(customer,0,map("recipientId",202,"amount",1));assertThrows(BusinessException.class,()->finance.transfer(customer,0,map("recipientId",202,"amount",1)));assertFalse(bool(db.pointAccount(0,201),"frozen"));assertEquals(2479,number(db.pointAccount(0,201),"available"));}
 @Test void pointReadModelsJoinActualCounterpartyAndPreserveScope(){finance.transfer(customer,0,map("recipientId",202,"amount",25));List<Map<String,Object>>rows=workflow.managementList(platform,0,"pointTransfers");assertEquals(1,rows.size());assertEquals(25,number(rows.get(0),"amount"));assertEquals(25,number(rows.get(0),"credit"));assertEquals(202,number(rows.get(0),"recipient_id"));assertThrows(BusinessException.class,()->workflow.managementList(owner,0,"points"));assertTrue(workflow.managementList(owner,2,"pointTransfers").isEmpty());}


 Map<String,Object> reportFilter(){String day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();return map("from",day,"to",day);}
 String report(Actor a,long shop,Map<String,Object>f){return str(workflow.createReport(a,shop,f),"id");}
 Map<String,Object> finishReport(Actor a,String id,String dimension){for(int i=0;i<10;i++){workflow.processReport(id);Map<String,Object>r=new HexuReports(db).view(a,id,dimension,1,1000);if("COMPLETED".equals(r.get("status")))return r;}throw new AssertionError("report did not finish");}
 @Test void reportSnapshotSurvivesLaterRefundAndAgentChange(){String order=create(2);complete(order);String id=report(owner,2,reportFilter());workflow.processReport(id);long line=db.jdbc().queryForObject("select id from hx_order_line where order_id=?",Long.class,order);String refund=str(finance.refundApply(customer,order,map("lineId",line,"qty",1,"type","RETURN","reason","报表快照测试")),"id");finance.refundReview(owner,refund,map("approve",true));finance.inspect(owner,refund,1);finance.refundConfirmed(refund);db.jdbc().update("update hx_agent set rank_no=2 where id=4");Map<String,Object>old=finishReport(owner,id,"AGENT"),sum=(Map<String,Object>)old.get("summary");assertEquals(4000,number(sum,"net_sales"));assertEquals(2800,number(sum,"distributionMargin"));assertEquals(2800,number(sum,"earnings"));Map<String,Object>row=(Map<String,Object>)((List<?>)old.get("rows")).get(0);assertEquals(4,number(row,"agent_id"));assertEquals(1,number(row,"rank_no"));Map<String,Object>next=(Map<String,Object>)finishReport(owner,report(owner,2,reportFilter()),"SKU").get("summary");assertEquals(2000,number(next,"net_sales"));assertEquals(1400,number(next,"distributionMargin"));}
 @Test void reportScopesIncludeHistoricalTeamAndRejectOtherOwnersAndStaff(){complete(create(1));Map<String,Object>f=reportFilter();f.put("agentId",2);f.put("rank",1);String id=report(owner,2,f);assertEquals(2000,number((Map<String,Object>)finishReport(owner,id,"SHOP").get("summary"),"net_sales"));f.put("includeTeam",false);assertEquals(0,number((Map<String,Object>)finishReport(owner,report(owner,2,f),"SHOP").get("summary"),"net_sales"));assertThrows(BusinessException.class,()->new HexuReports(db).view(new Actor(401,false,false),id,"SHOP",1,50));assertThrows(BusinessException.class,()->report(customer,2,reportFilter()));assertThrows(BusinessException.class,()->report(owner,0,reportFilter()));db.jdbc().update("insert into hx_staff values(777,2,'FINANCE')");Actor other=new Actor(777,false,true);assertThrows(BusinessException.class,()->new HexuReports(db).view(other,id,"SHOP",1,50));f=reportFilter();f.put("county","不存在县");assertEquals(0,number((Map<String,Object>)finishReport(platform,report(platform,0,f),"COUNTY").get("summary"),"net_sales"));}
 @Test void reportProcessesMoreThanOneThousandRowsAndExportsAllPages(){String order=create(1);complete(order);Map<String,Object>line=db.one("select * from hx_order_line where order_id=?",order);List<Object[]>batch=new ArrayList<>();for(int i=0;i<1005;i++)batch.add(new Object[]{order,"report-sku-"+i,"报表商品"+i,line.get("snapshot_json")});db.jdbc().batchUpdate("insert into hx_order_line(order_id,sku_id,name,qty,unit_price,paid,snapshot_json) values(?,?,?,1,2000,2000,?)",batch);String id=report(owner,2,reportFilter());workflow.processReport(id);assertEquals(1006,number(db.one("select * from hx_report_job where id=?",id),"total_rows"));workflow.processReport(id);Map<String,Object>running=new HexuReports(db).view(owner,id,"SKU",1,1000);assertEquals("RUNNING",running.get("status"));assertEquals(1000,number(running,"processed_rows"));assertFalse(running.containsKey("summary"));Map<String,Object>done=finishReport(owner,id,"SKU");assertEquals(1006,number(done,"totalGroups"));assertEquals(2012000,number((Map<String,Object>)done.get("summary"),"net_sales"));assertEquals(6,new HexuReports(db).rows(owner,id,"SKU",2,1000).size());workflow.processReport(id);assertEquals(1006,number(db.one("select * from hx_report_job where id=?",id),"processed_rows"));workflow.auditReportExport(owner,id,"SKU");assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='REPORT_EXPORT'",Integer.class));}
 @Test void reportChinaDateBoundaryAndZeroSalesInventory(){String order=create(1);complete(order);java.time.LocalDate day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));db.jdbc().update("update hx_order set created_at=? where id=?",Timestamp.from(day.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().plusSeconds(60)),order);String id=report(owner,2,reportFilter());Map<String,Object>r=finishReport(owner,id,"DAY");assertEquals(day.toString(),((Map<?,?>)((List<?>)r.get("rows")).get(0)).get("day"));List<Map<String,Object>>stock=new HexuReports(db).inventory(owner,id,1,100);assertEquals(8,stock.size());assertTrue(stock.stream().anyMatch(x->number(x,"quantity")==0&&"本期无销量".equals(x.get("stockStatus"))));Map<String,Object>stapler=stock.stream().filter(x->"stapler".equals(x.get("sku_id"))).findFirst().get();assertEquals(319,number(stapler,"available"));assertEquals("319.00",stapler.get("turnoverDays").toString());}
 @Test void reportEmptyTotalsAndUnknownWholesaleCostsAreExplicit(){Map<String,Object>f=reportFilter();f.put("skuId","missing");Map<String,Object>empty=finishReport(owner,report(owner,2,f),"SKU");assertEquals(100,number(empty,"progress"));assertEquals(0,number((Map<String,Object>)empty.get("summary"),"net_sales"));Map<String,Object>b=body("stapler",48);b.put("destinationShopId",2);String order=str(orders.create(owner,900,b),"id");Map<String,Object>p=orders.payment(owner,order);orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),number(p,"amount"),"REPORT-WHOLESALE");Map<String,Object>sum=(Map<String,Object>)finishReport(platform,report(platform,900,reportFilter()),"ORDER").get("summary");assertEquals(28800,number(sum,"unknown_cost_sales"));assertEquals(0,number(sum,"distributionMargin"));assertEquals(0,number(sum,"distribution_cost"));}
 @Test void reportRejectsFutureDatesAndCsvFormulaInjection(){Map<String,Object>f=reportFilter();f.put("to",java.time.LocalDate.now().plusDays(10).toString());assertThrows(BusinessException.class,()->report(owner,2,f));assertEquals("\"'=SUM(1,2)\"",HexuReportController.csv("=SUM(1,2)"));assertEquals("\"'  @SUM(A1)\"",HexuReportController.csv("  @SUM(A1)"));assertEquals("\"禾序\"\"商城\"",HexuReportController.csv("禾序\"商城"));}

 @Test void marketingPreviewUsesRealCustomerRulesWithoutOrderOrPointMutation(){workflow.save(owner,2,"promotion_rule",map("name","营销试算","type","LIMITED","skuIds","stapler","rateBps",8000,"startsAt",0,"expiresAt",System.currentTimeMillis()+86400000,"stackPoints",true),true);Map<String,Object>b=body("stapler",2);b.put("customerId",201);b.put("points",100);Map<String,Object>r=orders.marketingPreview(owner,2,b);assertEquals(4000,number(r,"subtotal"));assertEquals(800,number(r,"promotionDiscount"));assertEquals(100,number(r,"pointsDiscount"));assertEquals(3100,number(r,"total"));assertEquals(1200,number(r,"cost"));assertEquals(1900,number(r,"margin"));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_order",Integer.class));assertEquals(320,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));assertEquals(2480,number(db.pointSnapshot(0,201),"available"));b.put("customerId",401);assertThrows(BusinessException.class,()->orders.marketingPreview(owner,2,b));}
 @Test void typedNotificationsRenderTemplatesAndRespectPerEventPreferences(){workflow.save(owner,2,"notification_template",map("event","ORDER_PAID","title","{{shop}} 支付提醒","content","{{message}}，订单 {{reference}}"),true);workflow.save(owner,2,"system_parameter",map("unpaidMinutes",15,"autoReceiveDays",7,"afterSalesDays",7,"notifyShipped",false),true);String order=create(1);pay(order);Map<String,Object>m=db.one("select * from hx_message where reference_id=? and event_type='ORDER_PAID'",order);assertTrue(str(m,"body_text").contains(order));assertTrue(str(m,"title").contains("支付提醒"));orders.ship(owner,order,map("carrier","顺丰","tracking","NOTIFY123456"));assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_message where reference_id=? and event_type='ORDER_SHIPPED'",Integer.class,order));assertThrows(BusinessException.class,()->workflow.readMessage(new Actor(202,false,false),number(m,"id")));assertTrue(bool(workflow.readMessage(customer,number(m,"id")),"is_read"));assertThrows(BusinessException.class,()->workflow.save(owner,2,"notification_template",map("event","ORDER_PAID","content","{{password}}"),true));}
 @Test void reviewedDocumentMessagesUseKindAndLegacyRowsAreCorrectedOnlyOnRead(){
  String order=create(1);complete(order);
  String review=str(workflow.save(customer,2,"review",map("orderId",order,"skuId","stapler","rating",5,"content","合成评价"),false),"id");
  workflow.save(owner,2,"notification_template",map("event","SYSTEM","title","申请处理结果：{{message}}","content","{{message}}"),true);
  workflow.review(owner,review,map("decision","APPROVED"));
  Map<String,Object>current=db.one("select * from hx_message where reference_id=? and title like '申请处理结果：%' order by id desc limit 1",review);
  assertEquals("SYSTEM",current.get("event_type"));
  db.jdbc().update("insert into hx_message(shop_id,member_id,title,reference_id,event_type,body_text) values(2,201,'申请处理结果：APPROVED',?,'AGENT_UPDATED','申请处理结果：APPROVED')",review);
  long legacyId=db.jdbc().queryForObject("select max(id) from hx_message where reference_id=?",Long.class,review);
  Map<String,Object>shown=new HexuNotifications(db).list(customer,2).stream().filter(m->number(m,"id")==legacyId).findFirst().orElseThrow();
  assertEquals("SYSTEM",shown.get("event_type"));assertEquals("review",shown.get("reference_kind"));assertEquals("商品评价审核通过",shown.get("title"));
  Map<String,Object>opened=workflow.readMessage(customer,legacyId);
  assertEquals("SYSTEM",opened.get("event_type"));assertEquals("review",opened.get("reference_kind"));assertEquals("商品评价审核通过",opened.get("body_text"));
  Map<String,Object>raw=db.one("select event_type,title,body_text from hx_message where id=?",legacyId);
  assertEquals("AGENT_UPDATED",raw.get("event_type"));assertEquals("申请处理结果：APPROVED",raw.get("title"));
  assertThrows(BusinessException.class,()->workflow.readMessage(new Actor(202,false,false),legacyId));
  String support=str(workflow.save(customer,2,"support",map("question","其他问题","message","合成客服留言"),false),"id");
  workflow.review(owner,support,map("decision","APPROVED"));
  assertEquals("SYSTEM",db.one("select event_type from hx_message where reference_id=? order by id desc limit 1",support).get("event_type"));
  String cross=str(workflow.save(c1,3,"cross_purchase",map("sourceShopId",2,"items",List.of(map("skuId","stapler","qty",1)),"expiresAt",System.currentTimeMillis()+86400000),false),"id");
  workflow.review(platform,cross,map("decision","APPROVED"));
  assertEquals("AGENT_UPDATED",db.one("select event_type from hx_message where reference_id=? order by id desc limit 1",cross).get("event_type"));
  assertEquals("cross_purchase",new HexuNotifications(db).list(c1,3).stream().filter(m->cross.equals(m.get("reference_id"))).findFirst().orElseThrow().get("reference_kind"));
 }
 @Test void inventoryAlertOnlyFiresWhenCrossingThreshold(){db.jdbc().update("update hx_sku set available=11,warning_qty=10 where shop_id=2 and id='stapler'");db.inventory(2,"stapler",-1,0,"TEST","STOCK1");db.inventory(2,"stapler",-1,0,"TEST","STOCK2");assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_message where event_type='INVENTORY_LOW'",Integer.class));assertEquals(101,number(db.one("select * from hx_message where event_type='INVENTORY_LOW'"),"member_id"));}
 void staffUsers(){db.jdbc().execute("create table sys_user(user_id bigint primary key,user_name varchar(64),nick_name varchar(64),status varchar(1),del_flag varchar(1))");db.jdbc().update("insert into sys_user values(777,'report.staff','仓储同事','0','0')");}
 @Test void staffAssignmentsAreTenantScopedAndImmediatelyRevokeAccess(){staffUsers();HexuOperations operations=new HexuOperations(db);assertEquals(777,number(operations.lookup(owner,2,"report.staff"),"user_id"));workflow.assignStaff(owner,2,map("userId",777,"role","WAREHOUSE","enabled",true,"reason","新仓储同事授权"));Actor staff=new Actor(777,false,true);assertNotNull(workflow.managementList(staff,2,"inventory"));assertThrows(BusinessException.class,()->workflow.managementList(staff,2,"ledger"));assertThrows(BusinessException.class,()->workflow.assignStaff(staff,2,map("userId",777,"role","OWNER","enabled",true,"reason","自行提权")));assertThrows(BusinessException.class,()->operations.staff(owner,3));workflow.assignStaff(owner,2,map("userId",777,"role","WAREHOUSE","enabled",false,"reason","岗位调离撤销"));assertThrows(BusinessException.class,()->workflow.managementList(staff,2,"inventory"));assertEquals(0,operations.staff(owner,2).size());assertEquals("0",db.one("select * from sys_user where user_id=777").get("status"));}
 @Test void auditShowsActualActorRequestAndRedactsNestedSecrets(){org.springframework.mock.web.MockHttpServletRequest req=new org.springframework.mock.web.MockHttpServletRequest();req.setRequestURI("/hexu/app/management/stock-adjust");req.setRemoteAddr("192.168.5.12");req.addHeader("Idempotency-Key","qa-audit-request-01");org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(req));try{db.audit(owner,2,"TEST_AUDIT","R1",null,map("nested",map("accountNumber","6222123456789012","_secure_bank","cipher","password","hidden","phone","13812345678"),"quantity",5));}finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}long id=db.jdbc().queryForObject("select max(id) from hx_audit",Long.class);Map<String,Object>r=new HexuOperations(db).auditDetail(owner,2,id);assertEquals("MEMBER",r.get("actor_kind"));assertEquals("APP",r.get("source"));assertEquals("qa-audit-request-01",r.get("request_id"));assertEquals("192.168.5.*",r.get("ip_masked"));String serialized=db.json(r);assertFalse(serialized.contains("6222123456789012"));assertFalse(serialized.contains("cipher"));assertFalse(serialized.contains("hidden"));assertTrue(serialized.contains("****9012"));assertThrows(BusinessException.class,()->new HexuOperations(db).auditDetail(new Actor(401,false,false),2,id));}
 @Test void autoReceiptDelayUsesOrderSnapshotNotLaterConfiguration(){workflow.save(owner,2,"system_parameter",map("unpaidMinutes",15,"autoReceiveDays",7,"afterSalesDays",7),true);String order=create(1);workflow.save(owner,2,"system_parameter",map("unpaidMinutes",15,"autoReceiveDays",2,"afterSalesDays",2),true);pay(order);Map<String,Object>sent=orders.ship(owner,order,map("carrier","顺丰","tracking","SNAPSHOT1234"));assertEquals(7,number(db.parse(sent.get("shipping_json")),"autoReceiveDays"));}

 @Test void singletonSettingsAndEventTemplatesHaveOneEffectiveVersion(){Map<String,Object>one=workflow.save(owner,2,"system_parameter",map("unpaidMinutes",15,"autoReceiveDays",7,"afterSalesDays",7),true);Map<String,Object>next=map("id",one.get("id"),"unpaidMinutes",10,"autoReceiveDays",3,"afterSalesDays",9);workflow.save(owner,2,"system_parameter",next,true);assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_document where shop_id=2 and kind='system_parameter' and status='ACTIVE'",Integer.class));assertEquals("ARCHIVED",db.one("select * from hx_document where id=?",one.get("id")).get("status"));assertEquals(3,db.parameter(2,"system_parameter","autoReceiveDays",7));for(String content:Arrays.asList("旧通知 {{reference}}","新通知 {{reference}}"))workflow.save(owner,2,"notification_template",map("event","ORDER_PAID","content",content),true);workflow.save(owner,2,"notification_template",map("event","REFUND_UPDATED","content","退款状态 {{reference}}"),true);assertEquals(2,db.jdbc().queryForObject("select count(*) from hx_document where shop_id=2 and kind='notification_template' and status='ACTIVE'",Integer.class));db.message(2,201,"支付成功","P1");assertEquals("新通知 P1",db.one("select * from hx_message where reference_id='P1'").get("body_text"));}

 @Test void merchantEvidenceMustBeVerifiedInOrderAndResubmissionRevokesReadiness(){
  db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values('FILE-MERCHANT',2,101,'MERCHANT','image/png',?,?)",new byte[12],new byte[]{1});
  HexuMerchantOnboarding merchant=new HexuMerchantOnboarding(db,new UnconfiguredPaymentGateway(){@Override public boolean ready(String no){return true;}});
  org.springframework.test.util.ReflectionTestUtils.setField(merchant,"companyAppId","wx1234567890abcdef");
  org.springframework.test.util.ReflectionTestUtils.setField(merchant,"companyMerchantNo","12345678");
  assertFalse(bool(merchant.status(platform,900),"paymentReady"));
  assertThrows(BusinessException.class,()->merchant.requireReady(900,"SANDBOX-COMPANY"));
  Map<String,Object> submitted=merchant.submit(owner,2,map("subjectType","ENTERPRISE","subjectName","安溪商贸公司","legalRepresentative","张三","licenseNo","91350211123456789X","contactPhone","13812345678","attachmentIds",Collections.singletonList("FILE-MERCHANT")));
  assertEquals("PENDING",submitted.get("applicationStatus"));assertFalse(str(submitted,"contactPhone").contains("13812345678"));
  assertThrows(BusinessException.class,()->merchant.verify(platform,2,map("stage","APPLICATION","approve",true)));
  merchant.reference(owner,2,map("stage","APPLICATION","reference","APPLICATION-1234"));merchant.verify(platform,2,map("stage","APPLICATION","approve",true));
  assertThrows(BusinessException.class,()->merchant.authorize(owner,2,map("appId","wx0000000000000000","merchantNo","1234567890")));
  merchant.authorize(owner,2,map("appId","wx1234567890abcdef","merchantNo","1234567890"));merchant.reference(owner,2,map("stage","AUTHORIZATION","reference","AUTH-1234"));merchant.verify(platform,2,map("stage","AUTHORIZATION","approve",true));
  assertThrows(BusinessException.class,()->merchant.certificate(owner,2,map("serialNo","SERIAL1234","expiresAt",java.time.LocalDate.now().plusYears(1).toString())));
  merchant.certificate(owner,2,map("serialNo","SERIAL1234","expiresAt",java.time.LocalDate.now().plusYears(1).toString(),"contractedFeeBps",20,"settlementDays",7));merchant.reference(owner,2,map("stage","CERTIFICATE","reference","CERT-1234"));assertTrue(bool(merchant.verify(platform,2,map("stage","CERTIFICATE","approve",true)),"paymentReady"));
  merchant.reference(owner,2,map("stage","TRANSFER","reference","TRANSFER-1234"));assertEquals("PENDING",merchant.status(owner,2).get("transferAuthStatus"));merchant.verify(platform,2,map("stage","TRANSFER","approve",true));assertEquals("VERIFIED",merchant.status(owner,2).get("transferAuthStatus"));
  merchant.submit(owner,2,map("subjectType","ENTERPRISE","subjectName","安溪商贸公司","legalRepresentative","李四","licenseNo","91350211123456789X","contactPhone","13812345678","attachmentIds",Collections.singletonList("FILE-MERCHANT")));
  assertEquals("NOT_SUBMITTED",merchant.status(owner,2).get("authorizationStatus"));assertFalse(bool(merchant.status(owner,2),"paymentReady"));
 }
 @Test void shopPointRedemptionRecordsItsScopeAndReturnsToTheSameShop(){
  db.jdbc().update("update hx_sku set point_price=100 where shop_id=2 and id='towel'");
  long before=db.jdbc().queryForObject("select available from hx_points where member_id=201 and shop_id=2",Long.class);
  Map<String,Object>order=orders.redeem(customer,2,map("skuId","towel","qty",1,"scope",2,"address",map("name","兑换用户","detail","测试地址")));
  assertEquals(2,number(order,"points_scope"));
  assertEquals(before-100,db.jdbc().queryForObject("select available from hx_points where member_id=201 and shop_id=2",Long.class));
  long line=db.jdbc().queryForObject("select id from hx_order_line where order_id=?",Long.class,order.get("id"));
  String refund=str(finance.refundApply(customer,str(order,"id"),map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","兑换售后")),"id");
  finance.refundReview(owner,refund,map("approve",true));
  assertEquals(before,db.jdbc().queryForObject("select available from hx_points where member_id=201 and shop_id=2",Long.class));
 }
 @Test void policyConsentRequiresBothCurrentPublishedVersions(){
  HexuPolicies policies=new HexuPolicies(db);String text="这是正式测试协议正文，包含用户权利义务与处理方式。";
  policies.publish(platform,2,map("type","USER_AGREEMENT","version","1.0","title","用户协议","content",text));
  assertThrows(BusinessException.class,()->policies.consent(customer,2,map("versions",map("USER_AGREEMENT","1.0")),"TEST"));
  policies.publish(platform,2,map("type","PRIVACY_POLICY","version","1.0","title","隐私政策","content",text));
  assertThrows(BusinessException.class,()->policies.consent(customer,2,map("versions",map("USER_AGREEMENT","0.9","PRIVACY_POLICY","1.0")),"TEST"));
  assertEquals(2,policies.consent(customer,2,map("versions",map("USER_AGREEMENT","1.0","PRIVACY_POLICY","1.0")),"TEST").size());
  policies.publish(platform,2,map("type","PRIVACY_POLICY","version","1.1","title","隐私政策新版","content",text));
  assertThrows(BusinessException.class,()->policies.consent(customer,2,map("versions",map("USER_AGREEMENT","1.0","PRIVACY_POLICY","1.0")),"TEST"));
 }
 @Test void missingPoliciesNeverInventAnAgreement(){
  HexuPolicies policies=new HexuPolicies(db);
  assertTrue(policies.current(2).isEmpty());
  assertThrows(BusinessException.class,()->policies.consent(customer,2,map("versions",map("USER_AGREEMENT","old")),"TEST"));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_policy",Integer.class));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_policy_consent",Integer.class));
 }
 @Test void agentApplicationBindsThePublishedAgreementVersion(){
  HexuPolicies policies=new HexuPolicies(db);
  policies.publish(platform,2,map("type","AGENT_AGREEMENT","version","1.0","title","代理合作协议","content","这是用于测试的代理协议正文，包含申请授权与责任说明。"));
  db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values(?,?,?,?,?,?,?)",
      "FILE-AGENT-TEST",2,customer.id,"IDENTITY","image/png",new byte[12],new byte[]{1});
  Map<String,Object> current=(Map<String,Object>)policies.current(2).get("AGENT_AGREEMENT");
  Map<String,Object> form=map("rank",1,"name","测试代理","phone","13812345678","所在县域","安溪县",
      "accountName","测试代理","consent",true,"账户类型","微信零钱",
      "uploads",List.of("FILE-AGENT-TEST"),"agreementVersion","0.9","agreementPolicyId",current.get("id"));
  // 旧版本必须拒绝，成功申请只能保存服务端核实后的协议版本与编号。
  assertThrows(BusinessException.class,()->workflow.save(customer,2,"agent_application",form,false));
  form.put("agreementVersion","1.0");
  String id=str(workflow.save(customer,2,"agent_application",form,false),"id");
  Map<String,Object> saved=(Map<String,Object>)workflow.list(customer,2,"agent_application",false).stream()
      .filter(row->id.equals(row.get("id"))).findFirst().orElseThrow().get("body");
  assertEquals("1.0",saved.get("agreementVersion"));
  assertEquals(current.get("id"),saved.get("agreementPolicyId"));
  assertEquals(false,saved.get("agreementSandbox"));
  assertEquals("AGENT_APPLICATION",db.one("select source from hx_policy_consent where member_id=? and shop_id=? and policy_type=? and version=?",
      customer.id,2,"AGENT_AGREEMENT","1.0").get("source"));
 }
 @Test void approvedAgentInheritsFirstInvitationBindingForFutureEarnings(){
  Actor applicant=new Actor(205,false,false);
  db.jdbc().update("insert into hx_member(id,name,phone,authorized) values(205,'受邀新代理','13900000205',true)");
  String invite=str(catalog.invite(c1,2),"invite");
  catalog.bind(applicant,2,invite);
  assertEquals(4,number(db.one("select agent_id from hx_binding where member_id=205 and shop_id=2"),"agent_id"));
  assertEquals(4,number(db.one("select inviter_agent_id from hx_referral where member_id=205 and shop_id=2"),"inviter_agent_id"));
  String historical=str(orders.create(applicant,2,body("stapler",1)),"id");
  String oldSnapshot=str(db.one("select snapshot_json from hx_order_line where order_id=?",historical),"snapshot_json");
  HexuPolicies policies=new HexuPolicies(db);
  policies.publish(platform,2,map("type","AGENT_AGREEMENT","version","D107","title","代理合作协议","content","测试新代理关系和收益快照使用的协议正文。"));
  Map<String,Object> agreement=(Map<String,Object>)policies.current(2).get("AGENT_AGREEMENT");
  db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values(?,?,?,?,?,?,?)","FILE-D107",2,205,"IDENTITY","image/png",new byte[12],new byte[]{1});
  Map<String,Object> form=map("rank",1,"name","受邀新代理","phone","13900000205","所在县域","安溪县","accountName","受邀新代理","consent",true,"账户类型","微信零钱","uploads",List.of("FILE-D107"),"agreementVersion","D107","agreementPolicyId",agreement.get("id"));
  String application=str(workflow.save(applicant,2,"agent_application",form,false),"id");
  assertEquals(4,number(db.parse(db.one("select body_json from hx_document where id=?",application).get("body_json")),"parentId"));
  workflow.review(owner,application,map("decision","APPROVED"));
  long agentId=number(db.agent(205,2),"id");
  assertEquals(4,number(db.agent(205,2),"parent_id"));
  assertEquals(oldSnapshot,str(db.one("select snapshot_json from hx_order_line where order_id=?",historical),"snapshot_json"));
  String current=str(orders.create(applicant,2,body("stapler",1)),"id");
  Map<String,Object> snapshot=db.parse(db.one("select snapshot_json from hx_order_line where order_id=?",current).get("snapshot_json"));
  List<Map<String,Object>> chain=(List<Map<String,Object>>)snapshot.get("chain");
  assertEquals(agentId,number(chain.get(0),"agentId"));assertEquals(4,number(chain.get(1),"agentId"));
  assertTrue(db.jdbc().queryForObject("select count(*) from hx_earning where order_id=? and agent_id=4",Long.class,current)>0);
 }
 @Test void legacyAgentSupplementRequiresFreshAgreementConsent(){
  Actor applicant=new Actor(202,false,false);
  HexuPolicies policies=new HexuPolicies(db);
  policies.publish(platform,2,map("type","AGENT_AGREEMENT","version","2.0","title","代理合作协议","content","这是用于测试补签的代理协议正文，包含申请授权与责任说明。"));
  db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values(?,?,?,?,?,?,?)",
      "FILE-AGENT-LEGACY",2,applicant.id,"IDENTITY","image/png",new byte[12],new byte[]{1});
  Map<String,Object> form=map("id","DOC-AGENT-LEGACY","rank",1,"name","历史代理","phone","13812345678",
      "所在县域","安溪县","accountName","历史代理","consent",true,"账户类型","微信零钱",
      "uploads",List.of("FILE-AGENT-LEGACY"));
  db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values(?,2,202,'agent_application','SUPPLEMENT',?)",
      form.get("id"),db.json(form));
  // 旧申请的笼统勾选不能冒充现行协议版本，补签后才写同意记录。
  assertThrows(BusinessException.class,()->workflow.save(applicant,2,"agent_application",form,false));
  form.put("agreementReconsent",true);
  form.put("agreementVersion","2.0");
  form.put("agreementPolicyId",((Map<?,?>)policies.current(2).get("AGENT_AGREEMENT")).get("id"));
  workflow.save(applicant,2,"agent_application",form,false);
  assertEquals("2.0",db.parse(db.one("select body_json from hx_document where id=?",form.get("id")).get("body_json")).get("agreementVersion"));
  assertEquals("AGENT_APPLICATION",db.one("select source from hx_policy_consent where member_id=? and shop_id=? and policy_type=? and version=?",
      applicant.id,2,"AGENT_AGREEMENT","2.0").get("source"));
 }
 @Test void localSandboxGatewayKeepsChannelSettlementAndAuditClearlySynthetic(){
  org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest();
  request.setRemoteAddr("127.0.0.1");
  org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
  try {
   HexuSandboxPaymentGateway sandbox=new HexuSandboxPaymentGateway(db,finance);
   String order=create(1);Map<String,Object> payment=orders.payment(customer,order);
   assertEquals(true,sandbox.createPayment(payment,"unused").get("sandbox"));
   assertEquals("PENDING",sandbox.query("payment",str(payment,"id"),str(payment,"merchant_no")).get("status"));
   orders.paymentConfirmed(str(payment,"id"),str(payment,"merchant_no"),number(payment,"amount"),"SANDBOX-"+payment.get("id"));
   orders.ship(owner,order,map("carrier","测试物流","tracking","TESTSANDBOX123"));orders.receive(customer,order);
   String refund=str(finance.refundApply(customer,order,map("lineId",db.one("select id from hx_order_line where order_id=?",order).get("id"),"qty",1,"type","REFUND_ONLY","reason","沙盒渠道退款")),"id");
   finance.refundReview(owner,refund,map("approve",true));
   Map<String,Object> before=db.one("select * from hx_refund where id=?",refund);
   assertEquals(true,sandbox.submitRefund(before,str(payment,"merchant_no")).get("sandbox"));
   assertEquals(true,sandbox.submitRefund(before,str(payment,"merchant_no")).get("sandbox"));
   assertEquals("SUCCESS",sandbox.query("refund",refund,str(payment,"merchant_no")).get("status"));
   assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_audit where action='SANDBOX_REFUND' and reference_id=?",Integer.class,refund));
   request.setRemoteAddr("192.0.2.5");
   assertThrows(BusinessException.class,()->sandbox.query("refund",refund,str(payment,"merchant_no")));
  } finally {org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
 }
 @Test void wechatLoginRollsBackIdentityWithoutCurrentPolicyConsent(){
  HexuPolicies policies=new HexuPolicies(db);
  String content="正式测试条款：说明账户服务、个人信息处理和用户权利。";
  policies.publish(platform,2,map("type","USER_AGREEMENT","version","1.0","title","用户协议","content",content));
  policies.publish(platform,2,map("type","PRIVACY_POLICY","version","1.0","title","隐私政策","content",content));
  Map<String,Object> input=map("code","wx-test-code","consent",true,"shopId",2);
  assertThrows(BusinessException.class,()->wechat.login(input));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_wechat_account where open_id='test-login-openid'",Integer.class));
  input.put("versions",map("USER_AGREEMENT","1.0","PRIVACY_POLICY","0.9"));
  assertThrows(BusinessException.class,()->wechat.login(input));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_wechat_account where open_id='test-login-openid'",Integer.class));
  input.put("versions",map("USER_AGREEMENT","1.0","PRIVACY_POLICY","1.0"));
  wechat.login(input);
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_wechat_account where open_id='test-login-openid'",Integer.class));
  assertEquals(2,db.jdbc().queryForObject("select count(*) from hx_policy_consent",Integer.class));
 }
 @Test void pickAndDirectSaleAftersaleAreScopedToActualRecipientAndWholesale(){
  HexuFulfillment fulfillment=new HexuFulfillment(db);String retail=create(1);pay(retail);
  assertEquals("NOT_STARTED",fulfillment.pickList(owner,2).get(0).get("pickStatus"));
  fulfillment.pick(owner,retail,"start");assertEquals("IN_PROGRESS",fulfillment.pickList(owner,2).get(0).get("pickStatus"));
  fulfillment.pick(owner,retail,"complete");assertEquals("COMPLETED",fulfillment.print(owner,retail).get("pickStatus"));
  long line=db.jdbc().queryForObject("select id from hx_order_line where order_id=?",Long.class,retail);
  String customerRefund=str(finance.refundApply(customer,retail,map("lineId",line,"qty",1,"type","REFUND_ONLY","reason","质量问题")),"id");
  Map<String,Object> purchaseBody=body("stapler",48);purchaseBody.put("destinationShopId",2);purchaseBody.put("directShip",true);
  String purchase=str(orders.create(owner,900,purchaseBody),"id");Map<String,Object> payment=orders.payment(owner,purchase);orders.paymentConfirmed(str(payment,"id"),str(payment,"merchant_no"),number(payment,"amount"),"DIRECT-PAID");
  assertThrows(BusinessException.class,()->fulfillment.linkAftersale(owner,2,map("customerRefundId",customerRefund,"wholesaleOrderId",purchase)));
  finance.refundReview(owner,customerRefund,map("approve",true));
  assertEquals(purchase,fulfillment.aftersaleCandidates(owner,2,customerRefund).get(0).get("wholesaleOrderId"));
  assertEquals(purchase,fulfillment.linkAftersale(owner,2,map("customerRefundId",customerRefund,"wholesaleOrderId",purchase)).get("wholesaleOrderId"));
  assertThrows(BusinessException.class,()->fulfillment.linkAftersale(new Actor(401,false,false),3,map("customerRefundId",customerRefund,"wholesaleOrderId",purchase)));
 }
 @Test void shippingRequiresCompletedPickWhenGateEnabled(){
  String order=create(1);pay(order);
  HexuOrderService gated=new HexuOrderService(db,catalog);
  org.springframework.test.util.ReflectionTestUtils.setField(gated,"requirePick",true);
  HexuFulfillment fulfillment=new HexuFulfillment(db);
  Map<String,Object> shipment=map("carrier","测试物流","tracking","PICK12345678");
  assertThrows(BusinessException.class,()->gated.ship(owner,order,shipment));
  fulfillment.pick(owner,order,"start");
  assertThrows(BusinessException.class,()->gated.ship(owner,order,shipment));
  fulfillment.pick(owner,order,"complete");
  for(Map<String,Object> invalid:Arrays.asList(
      map("carrier","顺丰","tracking","短号"),
      map("carrier","顺丰","tracking","PICK-12345678"),
      map("carrier","顺丰","tracking"," PICK12345678 "),
      map("carrier","顺丰\n速运","tracking","PICK12345678"),
      map("carrier",String.join("",Collections.nCopies(61,"x")),"tracking","PICK12345678")))
    assertThrows(BusinessException.class,()->gated.ship(owner,order,invalid));
  assertEquals("PAID",db.one("select status from hx_order where id=?",order).get("status"));
  Map<String,Object> shipped=gated.ship(owner,order,map("carrier"," 测试物流 ","tracking","PICK12345678"));
  assertEquals("SHIPPED",shipped.get("status"));
  Map<String,Object> saved=db.parse(shipped.get("shipping_json"));
  assertEquals("测试物流",saved.get("carrier"));
  assertEquals("PICK12345678",saved.get("tracking"));
 }
 @Test void unsignedWechatCallbacksCannotEnterBusinessInbox(){
  WechatPayGateway gateway=new WechatPayGateway(db,new ObjectMapper());
  assertThrows(BusinessException.class,()->gateway.verifyNotification("{}",Collections.emptyMap()));
  assertThrows(BusinessException.class,()->gateway.verifyNotification("{}",mapString("Wechatpay-Signature","WECHATPAY/SIGNTEST/bad","Wechatpay-Serial","X","Wechatpay-Nonce","X","Wechatpay-Timestamp",String.valueOf(java.time.Instant.now().getEpochSecond()))));
  assertThrows(BusinessException.class,()->gateway.verifyNotification("{}",mapString("Wechatpay-Signature","invalid","Wechatpay-Serial","X","Wechatpay-Nonce","X","Wechatpay-Timestamp",String.valueOf(Long.MIN_VALUE))));
  assertThrows(BusinessException.class,()->gateway.verifyNotification("{}",mapString("Wechatpay-Signature","invalid","Wechatpay-Serial","X","Wechatpay-Nonce","X","Wechatpay-Timestamp","-1")));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_channel_event",Integer.class));
 }
 @Test void buyerRemarksPersistInCustomerAndManagementOrderReadback(){
  Map<String,Object> input=body("stapler",1);input.put("remark","请周末送达\n门口联系🌿");
  Map<String,Object> created=orders.create(customer,2,input);String id=str(created,"id");
  assertEquals(input.get("remark"),created.get("remark"));assertEquals(input.get("remark"),orders.detail(customer,id,false).get("remark"));
  assertEquals(input.get("remark"),orders.detail(owner,id,true).get("remark"));
  assertEquals(input.get("remark"),orders.list(customer,2,false).stream().filter(row->id.equals(str(row,"id"))).findFirst().get().get("remark"));
  input.put("remark",String.join("",Collections.nCopies(200,"🌿")));assertEquals(input.get("remark"),orders.create(customer,2,input).get("remark"));
  assertEquals("",orders.create(customer,2,body("stapler",1)).get("remark"));
  assertThrows(BusinessException.class,()->orders.detail(new Actor(202,false,false),id,false));
 }
 @Test void invalidBuyerRemarksRejectBeforeAnyInventoryOrOrderChange(){
  for(Object invalid:Arrays.asList(String.join("",Collections.nCopies(201,"🌿")),123,null,"a\u0000b")){
   Map<String,Object> input=body("stapler",1);input.put("remark",invalid);assertThrows(BusinessException.class,()->orders.create(customer,2,input));
  }
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_order",Integer.class));
  assertEquals(320,db.jdbc().queryForObject("select available from hx_sku where shop_id=2 and id='stapler'",Integer.class));
 }
 @Test void settlementAccountsValidateTheirChannelAndKeepBankDetailsEncrypted(){
  org.springframework.test.util.ReflectionTestUtils.setField(workflow,"sensitiveKey","settlement-test-encryption-key-32-characters");
  for(Map<String,Object> input:Arrays.asList(map("channel","INVALID","accountName","测试人"),map("channel","BANK","accountName","测试人","bank","1234","bankName","测试银行"),map("channel","BANK","accountName","测试人","bank","6222021234567890","bankName",""),map("channel","BANK","accountName","","bank","6222021234567890","bankName","测试银行"),map("channel","BANK","accountName","测试人","bank","****7890","bankName","测试银行")))
   assertThrows(BusinessException.class,()->workflow.save(customer,2,"settlement_account",input,false));
  Map<String,Object> input=map("channel","BANK","accountName","测试人","bank","6222021234567890","bankName","测试银行");
  Map<String,Object> saved=workflow.save(customer,2,"settlement_account",input,false);String id=str(saved,"id");Map<String,Object> stored=db.parse(saved.get("body_json"));
  assertEquals("PENDING",saved.get("status"));assertEquals("****7890",stored.get("bank"));assertFalse(str(stored,"_secure_bank").isEmpty());
  assertFalse(str(saved,"body_json").contains("6222021234567890"));assertFalse(input.containsKey("_secure_bank"));
  Map<String,Object> visible=(Map<String,Object>)workflow.list(customer,2,"settlement_account",false).get(0).get("body");assertFalse(visible.containsKey("_secure_bank"));
  Map<String,Object> modify=map("id",id,"channel","BANK","accountName","新开户姓名","bank","****7890","bankName","测试银行");
  assertThrows(BusinessException.class,()->workflow.save(customer,2,"settlement_account",modify,false));
  workflow.review(owner,id,map("decision","REJECTED","reason","姓名有误"));
  Map<String,Object> resubmitted=workflow.save(customer,2,"settlement_account",modify,false);
  assertEquals("PENDING",resubmitted.get("status"));assertEquals(stored.get("_secure_bank"),db.parse(resubmitted.get("body_json")).get("_secure_bank"));
  workflow.review(owner,id,map("decision","APPROVED"));assertThrows(BusinessException.class,()->workflow.save(customer,2,"settlement_account",modify,false));
  assertEquals("PENDING",workflow.save(customer,2,"settlement_account",map("channel","BALANCE","accountName","测试人","bank","bad-unused"),false).get("status"));
 }
 @Test void documentReviewRolesMatchFinanceAndSupportScopes(){
  db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(710,2,'FINANCE'),(711,2,'SUPPORT'),(712,3,'FINANCE')");
  Actor financeActor=new Actor(710,false,true),supportActor=new Actor(711,false,true),otherShopFinance=new Actor(712,false,true);
  String account=str(workflow.save(customer,2,"settlement_account",map("channel","BALANCE","accountName","测试人"),false),"id");
  assertThrows(BusinessException.class,()->workflow.review(supportActor,account,map("decision","APPROVED")));
  assertThrows(BusinessException.class,()->workflow.review(otherShopFinance,account,map("decision","APPROVED")));
  assertEquals("APPROVED",workflow.review(financeActor,account,map("decision","APPROVED")).get("status"));
  String support=str(workflow.save(customer,2,"support",map("question","其他问题","message","测试工单"),false),"id");
  assertThrows(BusinessException.class,()->workflow.review(financeActor,support,map("decision","APPROVED")));
  assertEquals("APPROVED",workflow.review(supportActor,support,map("decision","APPROVED")).get("status"));
  String application=str(workflow.save(customer,2,"shop_application",map("name","新商城","county","新县"),false),"id");
  assertThrows(BusinessException.class,()->workflow.review(financeActor,application,map("decision","APPROVED")));
  assertThrows(BusinessException.class,()->workflow.review(owner,application,map("decision","APPROVED")));
 }
 @Test void invitationContextIsReadOnlyAndUsesVerifiedAgentAndExistingBinding(){
  String token=str(catalog.invite(new Actor(103,false,false),2),"invite");
  Map<String,Object> preview=catalog.invitationContext(customer,2,token);
  assertEquals(true,preview.get("valid"));assertEquals(true,preview.get("bound"));
  Map<String,Object> inviter=(Map<String,Object>)preview.get("inviter"),binding=(Map<String,Object>)preview.get("currentBinding");
  assertEquals(3,number(inviter,"id"));assertEquals("C 云代理",inviter.get("name"));assertFalse(inviter.containsKey("phone"));
  assertEquals(4,number(binding,"agentId"));assertEquals("C1 云代理",binding.get("name"));assertFalse(binding.containsKey("phone"));
  assertEquals(4,number(db.one("select agent_id from hx_binding where member_id=201 and shop_id=2"),"agent_id"));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_referral where member_id=201",Integer.class));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_invite",Integer.class));
  assertEquals(false,catalog.invitationContext(customer,3,token).get("valid"));
  assertEquals(false,catalog.invitationContext(customer,2,"missing").get("valid"));
 }
 @Test void expiredDisabledAndInactiveAgentInvitationsCannotBeUsed(){
  String token=str(catalog.invite(new Actor(103,false,false),2),"invite");
  db.jdbc().update("delete from hx_binding where member_id=201");
  db.jdbc().update("update hx_invite set expires_at=? where id=?",Timestamp.from(java.time.Instant.now().minusSeconds(60)),token);
  Map<String,Object> expired=catalog.invitationContext(customer,2,token);assertEquals(false,expired.get("valid"));assertNull(expired.get("inviter"));assertEquals(false,expired.get("bound"));
  assertThrows(BusinessException.class,()->catalog.bind(customer,2,token));
  db.jdbc().update("update hx_invite set expires_at=?,status='DISABLED' where id=?",Timestamp.from(java.time.Instant.now().plusSeconds(3600)),token);
  assertEquals(false,catalog.invitationContext(customer,2,token).get("valid"));
  db.jdbc().update("update hx_invite set status='ACTIVE' where id=?",token);db.jdbc().update("update hx_agent set status='INACTIVE' where id=3");
  assertEquals(false,catalog.invitationContext(customer,2,token).get("valid"));assertThrows(BusinessException.class,()->catalog.bind(customer,2,token));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_binding where member_id=201",Integer.class));
 }
 void attachment(String id,long member,long shop,String purpose){db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values(?,?,?,?,'image/png',?,?)",id,shop,member,purpose,new byte[12],new byte[]{1});}
 @Test void sameShopEditorsRetainOnlyImagesBoundToTheEditedStorefrontDocument(){
  db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(750,2,'OPERATOR'),(751,2,'CATALOG')");
  Actor operator=new Actor(750,false,true),catalogEditor=new Actor(751,false,true);
  attachment("FILE_DECOR_A",101,2,"STOREFRONT");attachment("FILE_DECOR_B",101,2,"STOREFRONT");attachment("FILE_DECOR_C",750,2,"STOREFRONT");
  Map<String,Object> original=workflow.save(owner,2,"decoration",map("name","原装修","uploads",Arrays.asList("FILE_DECOR_A"),"assets",map("hero","FILE_DECOR_A"),"banners",Arrays.asList(map("image","FILE_DECOR_B"))),true);
  Map<String,Object> edited=workflow.save(operator,2,"decoration",map("id",original.get("id"),"name","运营更新","uploads",Arrays.asList("FILE_DECOR_A","FILE_DECOR_B","FILE_DECOR_C"),"assets",map("hero","FILE_DECOR_A","uploaded","FILE_DECOR_C"),"banners",Arrays.asList(map("image","FILE_DECOR_B"))),true);
  assertEquals(original.get("id"),edited.get("id"));assertEquals(101,number(edited,"member_id"));
  assertEquals("FILE_DECOR_A",((Map<?,?>)db.parse(edited.get("body_json")).get("assets")).get("hero"));
  Map<String,Object> catalogEdit=workflow.save(catalogEditor,2,"decoration",map("id",original.get("id"),"name","商品岗位更新","uploads",Arrays.asList("FILE_DECOR_A","FILE_DECOR_B","FILE_DECOR_C")),true);
  assertEquals("商品岗位更新",db.parse(catalogEdit.get("body_json")).get("name"));
 }
 @Test void storefrontEditsRejectOtherDocumentsUnboundImagesCrossShopAndPrivateImages(){
  db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(750,2,'OPERATOR')");Actor operator=new Actor(750,false,true);
  attachment("FILE_BOUND",101,2,"STOREFRONT");attachment("FILE_UNBOUND",101,2,"STOREFRONT");attachment("FILE_CROSSSHOP",750,3,"STOREFRONT");attachment("FILE_PRIVATE",750,2,"IDENTITY");
  Map<String,Object> original=workflow.save(owner,2,"decoration",map("name","原装修","uploads",Arrays.asList("FILE_BOUND"),"assets",map("hero","FILE_BOUND")),true);
  db.jdbc().update("insert into hx_document(id,shop_id,member_id,kind,status,body_json) values('OTHERDECOR',2,101,'decoration','ACTIVE',?)",db.json(map("uploads",Arrays.asList("FILE_UNBOUND"))));
  for(String file:Arrays.asList("FILE_UNBOUND","FILE_CROSSSHOP")){
   assertThrows(BusinessException.class,()->workflow.save(operator,2,"decoration",map("id",original.get("id"),"uploads",Arrays.asList(file)),true));
   assertThrows(BusinessException.class,()->workflow.save(operator,2,"decoration",map("id",original.get("id"),"assets",map("hero",file)),true));
  }
  assertThrows(BusinessException.class,()->workflow.save(operator,2,"decoration",map("uploads",Arrays.asList("FILE_BOUND")),true));
  assertThrows(BusinessException.class,()->workflow.save(operator,2,"decoration",map("id",original.get("id"),"assets",map("hero","FILE_PRIVATE")),true));
  assertThrows(BusinessException.class,()->workflow.save(operator,3,"decoration",map("id",original.get("id"),"uploads",Arrays.asList("FILE_BOUND")),true));
  assertEquals("原装修",db.parse(db.one("select body_json from hx_document where id=?",original.get("id")).get("body_json")).get("name"));
 }
 @Test void retainedDocumentAttachmentsStillRequireThatBusinessRole(){
  db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(750,2,'OWNER'),(751,2,'OPERATOR')");
  attachment("FILE_MERCHANT_BOUND",101,2,"MERCHANT");
  Map<String,Object> original=workflow.save(owner,2,"merchant_application",map("name","进件资料","uploads",Arrays.asList("FILE_MERCHANT_BOUND")),true);
  assertThrows(BusinessException.class,()->workflow.save(new Actor(751,false,true),2,"merchant_application",map("id",original.get("id"),"uploads",Arrays.asList("FILE_MERCHANT_BOUND")),true));
  Map<String,Object> edited=workflow.save(new Actor(750,false,true),2,"merchant_application",map("id",original.get("id"),"name","有权限人员更新","uploads",Arrays.asList("FILE_MERCHANT_BOUND")),true);
  assertEquals("有权限人员更新",db.parse(edited.get("body_json")).get("name"));
 }
 @Test void pageSchemaRetainsBoundNestedImagesButCannotPublishSomeoneElsesNewImage(){
  db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(750,2,'CATALOG')");Actor editor=new Actor(750,false,true);
  attachment("FILE_PAGE_BOUND",101,2,"STOREFRONT");attachment("FILE_PAGE_UNBOUND",101,2,"STOREFRONT");
  Map<String,Object> screens=map("M03",map("blocks",Arrays.asList(map("type","hero","asset","FILE_PAGE_BOUND"))));
  Map<String,Object> original=workflow.save(owner,2,"page_schema",map("screens",screens),true);
  assertNotNull(workflow.save(editor,2,"page_schema",map("id",original.get("id"),"screens",screens),true));
  Map<String,Object> bad=map("M03",map("blocks",Arrays.asList(map("type","hero","asset","FILE_PAGE_UNBOUND"))));
  assertThrows(BusinessException.class,()->workflow.save(editor,2,"page_schema",map("id",original.get("id"),"screens",bad),true));
 }
 @Test void memberDocumentAttachmentsDoNotGainManagementRetentionPrivileges(){
  attachment("MEMBER_OWN",201,2,"SUPPORT");attachment("MEMBER_OTHER",202,2,"SUPPORT");
  Map<String,Object> original=workflow.save(customer,2,"support",map("question","其他问题","message","附件权限工单","uploads",Arrays.asList("MEMBER_OWN")),false);
  db.jdbc().update("update hx_document set status='REJECTED',body_json=? where id=?",db.json(map("uploads",Arrays.asList("MEMBER_OTHER"))),original.get("id"));
  assertThrows(BusinessException.class,()->workflow.save(customer,2,"support",map("id",original.get("id"),"uploads",Arrays.asList("MEMBER_OTHER")),false));
  assertThrows(BusinessException.class,()->workflow.save(customer,2,"support",map("uploads","MEMBER_OWN"),false));
 }
 @Test void ownersCanRetainExistingLinkConfirmationWithoutOwningItsOriginalUpload(){
  db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(750,2,'OWNER')");Actor editor=new Actor(750,false,true);
  Map<String,Object> original=linkRule("KEEP_RELATION");
  Map<String,Object> edited=workflow.save(editor,2,"link_campaign",map("id",original.get("id"),"name","另一管理人员更新"),true);
  Map<String,Object> body=db.parse(edited.get("body_json"));
  assertNotEquals(original.get("id"),edited.get("id"));assertEquals("FINANCE_PROOF",body.get("confirmationFile"));assertEquals(original.get("id"),body.get("previousVersionId"));
  attachment("FILE_LINK_NEW",750,2,"LINK_CONFIRMATION");
  Map<String,Object> replaced=workflow.save(editor,2,"link_campaign",map("id",edited.get("id"),"confirmationFile","FILE_LINK_NEW"),true);
  assertEquals("FILE_LINK_NEW",db.parse(replaced.get("body_json")).get("confirmationFile"));
 }
 @Test void linkConfirmationRetentionDoesNotAllowOtherAttachmentsRolesOrDisabledStateBypasses(){
  db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(750,2,'OWNER'),(751,2,'OPERATOR')");Actor editor=new Actor(750,false,true);
  Map<String,Object> first=linkRule("KEEP_RELATION");attachment("FILE_LINK_OTHER",101,2,"LINK_CONFIRMATION");attachment("FILE_LINK_CROSSSHOP",750,3,"LINK_CONFIRMATION");
  Map<String,Object> original=workflow.save(owner,2,"link_campaign",map("id",first.get("id"),"uploads",Arrays.asList("FINANCE_PROOF","FILE_LINK_OTHER")),true);
  assertThrows(BusinessException.class,()->workflow.save(editor,2,"link_campaign",map("id",original.get("id"),"confirmationFile","FILE_LINK_OTHER"),true));
  assertThrows(BusinessException.class,()->workflow.save(editor,2,"link_campaign",map("id",original.get("id"),"confirmationFile","FILE_LINK_CROSSSHOP"),true));
  assertThrows(BusinessException.class,()->workflow.save(new Actor(751,false,true),2,"link_campaign",map("id",original.get("id")),true));
  assertThrows(BusinessException.class,()->workflow.save(editor,2,"link_campaign",map("enabled",false,"confirmationFile","FILE_LINK_OTHER"),true));
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_document where kind='link_campaign' and status='ACTIVE'",Integer.class));
 }
 String approvedReturn(){String order=create(1);complete(order);long line=number(db.one("select id from hx_order_line where order_id=?",order),"id");String refund=str(finance.refundApply(customer,order,map("lineId",line,"qty",1,"type","RETURN","reason","退货凭证测试")),"id");finance.refundReview(owner,refund,map("approve",true));return refund;}
 @Test void returnWaybillProofsPersistAndReadBackWithTheRealRefundShop(){
  String refund=approvedReturn();attachment("RETURN_OWN",201,2,"RETURN");
  Map<String,Object> saved=finance.returnTracking(customer,refund,map("shopId",3,"carrier","顺丰速运","tracking","SF12345678","uploads",Arrays.asList("RETURN_OWN")));
  Map<String,Object> returned=db.parse(saved.get("return_json"));assertEquals("顺丰速运",returned.get("carrier"));assertEquals(Arrays.asList("RETURN_OWN"),returned.get("uploads"));
  Map<String,Object> retained=finance.returnTracking(customer,refund,map("carrier","顺丰速运","tracking","SF87654321"));assertEquals(Arrays.asList("RETURN_OWN"),db.parse(retained.get("return_json")).get("uploads"));
  Map<String,Object> cleared=finance.returnTracking(customer,refund,map("carrier","顺丰速运","tracking","SF87654321","uploads",Collections.emptyList()));assertEquals(Collections.emptyList(),db.parse(cleared.get("return_json")).get("uploads"));
  assertEquals("WAIT_RETURN",cleared.get("status"));
 }
 @Test void returnWaybillProofsRejectForeignPrivateCrossShopAndInvalidListsAtomically(){
  String refund=approvedReturn();attachment("RETURN_OTHER",202,2,"RETURN");attachment("RETURN_CROSSSHOP",201,3,"RETURN");attachment("RETURN_IDENTITY",201,2,"IDENTITY");attachment("RETURN_OWN",201,2,"RETURN");
  Map<String,Object> before=finance.returnTracking(customer,refund,map("carrier","顺丰","tracking","SF12345678","uploads",Arrays.asList("RETURN_OWN")));
  for(Object invalid:Arrays.asList(Arrays.asList("RETURN_OTHER"),Arrays.asList("RETURN_CROSSSHOP"),Arrays.asList("RETURN_IDENTITY"),Arrays.asList("RETURN_OWN","RETURN_OWN"),Collections.nCopies(10,"RETURN_OWN"),"RETURN_OWN",null)){
   assertThrows(BusinessException.class,()->finance.returnTracking(customer,refund,map("carrier","顺丰","tracking","SF87654321","uploads",invalid)));
  }
  assertEquals(before.get("return_json"),db.one("select return_json from hx_refund where id=?",refund).get("return_json"));
 }
 @Test void returnWaybillProofsRemainRestrictedToApplicantAndReturnState(){
  String refund=approvedReturn();Map<String,Object> body=map("carrier","顺丰","tracking","SF12345678","uploads",Collections.emptyList());
  assertThrows(BusinessException.class,()->finance.returnTracking(new Actor(202,false,false),refund,body));
  assertThrows(BusinessException.class,()->finance.returnTracking(platform,refund,body));
  db.jdbc().update("update hx_refund set status='APPROVED' where id=?",refund);assertThrows(BusinessException.class,()->finance.returnTracking(customer,refund,body));
  assertNull(db.one("select return_json from hx_refund where id=?",refund).get("return_json"));
 }
 @Test void migrationBlankDateApprovesImmediatelyWithoutInvalidTimestamp(){
  String id=str(workflow.save(customer,2,"migration",map("targetShopId",2,"targetAgentId",3,"effectiveAt",""),false),"id");
  assertEquals("",db.parse(db.one("select body_json from hx_document where id=?",id).get("body_json")).get("effectiveAt"));
  workflow.review(owner,id,map("decision","APPROVED"));assertEquals(3,number(db.one("select * from hx_binding where member_id=201 and shop_id=2"),"agent_id"));
 }
 @Test void migrationValidDateOnlyAndFractionalTimestampAreCanonicalAndScheduled(){
   int member=201;
   for(String input:Arrays.asList("2096-02-29","2096-02-29 12:30:45.123456789")){
    Map<String,Object> saved=workflow.save(new Actor(member++,false,false),2,"migration",map("targetShopId",2,"targetAgentId",3,"effectiveAt",input),false);
   String expected=input.length()==10?input+" 00:00:00.0":input;
   assertEquals(expected,db.parse(saved.get("body_json")).get("effectiveAt"));workflow.review(owner,str(saved,"id"),map("decision","APPROVED"));assertEquals("SCHEDULED",db.one("select status from hx_document where id=?",saved.get("id")).get("status"));
  }
 }
 @Test void migrationInvalidCalendarDatesAndNullRejectBeforePersisting(){
  for(Object input:Arrays.asList(null,12,"2027-02-29","2026-04-31","0000-01-01","2026-13-01"," 00:00:00","2026-09-27 24:00:00","2026-09-27 00:00:00."))assertThrows(BusinessException.class,()->workflow.save(customer,2,"migration",map("targetShopId",2,"targetAgentId",3,"effectiveAt",input),false));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='migration'",Integer.class));
 }
 Map<String,Object> limitedRule(){return map("name","限时活动","type","LIMITED","rateBps",8500,"startsAt",0,"expiresAt",System.currentTimeMillis()+86400000,"quantity",10,"perMember",1);}
 Map<String,Object> bundleRule(){return map("name","组合活动","type","BUNDLE","items",Collections.singletonList(map("skuId","stapler","qty",1)),"price",1500,"threshold",0,"startsAt",0,"expiresAt",System.currentTimeMillis()+86400000,"quantity",10,"perMember",1);}
 @Test void marketingRequiresANameBeforePublishing(){
  for(String kind:Arrays.asList("promotion_rule","bundle")){
   Map<String,Object> input="bundle".equals(kind)?bundleRule():limitedRule();input.put("name","  ");
   assertThrows(BusinessException.class,()->workflow.save(owner,2,kind,input,true));
   assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind=?",Integer.class,kind));
  }
 }
 @Test void promotionExplicitNullNumbersRejectRatherThanBecomingZero(){
  for(String key:Arrays.asList("threshold","rateBps","quantity","perMember","startsAt","expiresAt")){Map<String,Object> input=limitedRule();input.put(key,null);assertThrows(BusinessException.class,()->workflow.save(owner,2,"promotion_rule",input,true));}
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='promotion_rule'",Integer.class));
 }
 @Test void marketingRejectsUnknownSkuScopeBeforePublishing(){
  for(Object ids:Arrays.asList("missing-sku","stapler,missing-sku",List.of("missing-sku"))){
   Map<String,Object> input=limitedRule();input.put("skuIds",ids);
   assertThrows(BusinessException.class,()->workflow.save(owner,2,"promotion_rule",input,true));
  }
  Map<String,Object> bundle=bundleRule();bundle.put("qualifierSkuIds","missing-sku");
  assertThrows(BusinessException.class,()->workflow.save(owner,2,"bundle",bundle,true));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind in ('promotion_rule','bundle')",Integer.class));
  Map<String,Object> valid=limitedRule();valid.put("skuIds","stapler");
  String specific=str(workflow.save(owner,2,"promotion_rule",valid,true),"id");
  assertEquals(1,db.jdbc().queryForObject("select count(*) from hx_document where kind='promotion_rule'",Integer.class));
  db.jdbc().update("update hx_document set status='ARCHIVED' where id=?",specific);
  Map<String,Object> all=limitedRule();all.put("skuIds",List.of("全部商品"));
  workflow.save(owner,2,"promotion_rule",all,true);
  assertEquals(1700,number(orders.create(customer,2,body("stapler",1)),"total"));
 }
 @Test void bundleNullAndFractionalLimitsRejectRatherThanRemovingLimits(){
  for(String key:Arrays.asList("price","threshold","quantity","perMember","startsAt","expiresAt")){Map<String,Object> input=bundleRule();input.put(key,null);assertThrows(BusinessException.class,()->workflow.save(owner,2,"bundle",input,true));}
  for(Object value:Arrays.asList("NaN","Infinity",1.5,-1))for(String key:Arrays.asList("quantity","perMember")){Map<String,Object> input=bundleRule();input.put(key,value);assertThrows(BusinessException.class,()->workflow.save(owner,2,"bundle",input,true));}
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='bundle'",Integer.class));
 }
 @Test void marketingExplicitZeroKeepsUnlimitedQuantityAndMemberSemantics(){
  Map<String,Object> promotion=limitedRule();promotion.put("quantity",0);promotion.put("perMember",0);String ref=str(workflow.save(owner,2,"promotion_rule",promotion,true),"id");
  for(int i=0;i<2;i++)assertEquals(1700,number(orders.create(customer,2,body("stapler",1)),"total"));
  assertEquals(0,number(db.parse(db.one("select body_json from hx_document where id=?",ref).get("body_json")),"perMember"));
  db.jdbc().update("update hx_document set status='ARCHIVED' where id=?",ref);Map<String,Object> bundle=bundleRule();bundle.put("quantity",0);bundle.put("perMember",0);workflow.save(owner,2,"bundle",bundle,true);for(int i=0;i<2;i++)assertEquals(1500,number(orders.create(customer,2,body("stapler",1)),"total"));
 }
 @Test void wholesaleRemarkPersistsAndReadsBackUnicodeBoundaryAndBlank(){
  for(String remark:Arrays.asList("按箱分开送达🌿",String.join("",Collections.nCopies(200,"🌿")),"")){
   Map<String,Object> body=body("stapler",48);body.put("destinationShopId",2);body.put("remark",remark);Map<String,Object> created=orders.create(owner,900,body);
   assertEquals(remark,created.get("remark"));assertEquals(remark,orders.detail(owner,str(created,"id"),false).get("remark"));assertEquals(900,number(created,"shop_id"));
  }
 }
 @Test void wholesaleInvalidRemarkRejectsBeforeOrderMutation(){
  for(Object remark:Arrays.asList(null,12,"隐藏\u0000文本",String.join("",Collections.nCopies(201,"🌿")))){
   Map<String,Object> body=body("stapler",48);body.put("destinationShopId",2);body.put("remark",remark);assertThrows(BusinessException.class,()->orders.create(owner,900,body));
  }
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_order",Integer.class));
 }
 Map<String,Object> simpleAssessment(){return map("periodMonths",1,"periodKind","MONTH","operator","AND","salesThreshold",0,"directCount",0,"repeatRate",0);}
 long shanghaiToday(){return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();}
 @Test void assessmentTodayIsActiveWhileFutureDateWaits(){
  Map<String,Object> input=simpleAssessment();input.put("effectiveAt",shanghaiToday());workflow.save(owner,2,"assessment_rule",input,true);assertTrue(bool(new HexuAssessment(db).evaluate(104,2),"eligible"));
  input.put("effectiveAt",shanghaiToday()+86400000);workflow.save(owner,2,"assessment_rule",input,true);assertFalse(bool(new HexuAssessment(db).evaluate(104,2),"eligible"));
 }
 @Test void assessmentInvalidOrExpiredEffectiveTimeRejectsBeforeVersionMutation(){
  for(Object time:Arrays.asList(null,"",0,shanghaiToday()-1,Double.NaN,Double.POSITIVE_INFINITY,1.5,java.time.LocalDate.of(2101,1,1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli())){
   Map<String,Object> input=simpleAssessment();input.put("effectiveAt",time);assertThrows(BusinessException.class,()->workflow.save(owner,2,"assessment_rule",input,true));
  }
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='assessment_rule'",Integer.class));
 }
 @Test void assessmentNullThresholdsRejectButMissingOptionalDateKeepsCompatibility(){
  for(String key:Arrays.asList("salesThreshold","directCount","repeatRate")){Map<String,Object> input=simpleAssessment();input.put(key,null);assertThrows(BusinessException.class,()->workflow.save(owner,2,"assessment_rule",input,true));}
  assertEquals("ACTIVE",workflow.save(owner,2,"assessment_rule",simpleAssessment(),true).get("status"));
 }
 @Test void assessmentNamedPlanRejectsBlankName(){
  Map<String,Object> input=simpleAssessment();input.put("name","  ");
  assertThrows(BusinessException.class,()->workflow.save(owner,2,"assessment_rule",input,true));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='assessment_rule'",Integer.class));
 }
 @Test void couponWithBlankNameRejectsBeforePublish(){
  Map<String,Object> input=map("name","  ","discount",100,"threshold",1000,"quantity",1,"expiresAt",System.currentTimeMillis()+86400000);
  assertThrows(BusinessException.class,()->workflow.save(owner,2,"coupon",input,true));
  assertEquals(0,db.jdbc().queryForObject("select count(*) from hx_document where kind='coupon'",Integer.class));
 }
 Map<String,String> mapString(String... pairs){Map<String,String> result=new HashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],pairs[i+1]);return result;}
}
