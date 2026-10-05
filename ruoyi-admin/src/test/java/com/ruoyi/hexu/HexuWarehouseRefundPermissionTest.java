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
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real services and transactions against a dedicated in-memory database, never a live tenant. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=HexuWarehouseRefundPermissionTest.Config.class)
@TestPropertySource(properties="hexu.fulfillment.require-pick=false")
class HexuWarehouseRefundPermissionTest {
 @Configuration @EnableTransactionManagement static class Config {
  @Bean DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:hexuwarehouserefundpermission;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
  @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
  @Bean ObjectMapper mapper(){return new ObjectMapper();}
  @Bean PlatformTransactionManager tx(DataSource source){return new DataSourceTransactionManager(source);}
  @Bean HexuStore store(JdbcTemplate jdbc,ObjectMapper mapper){return new HexuStore(jdbc,mapper);}
  @Bean HexuCatalogService catalog(HexuStore db){return new HexuCatalogService(db);}
  @Bean HexuOrderService orders(HexuStore db,HexuCatalogService catalog){return new HexuOrderService(db,catalog);}
  @Bean HexuFinanceService finance(HexuStore db){return new HexuFinanceService(db);}
  @Bean HexuWorkflowService workflow(HexuStore db){return new HexuWorkflowService(db);}
  @Bean HexuFulfillment fulfillment(HexuStore db){return new HexuFulfillment(db);}
 }
 @Autowired DataSource source;
 @Autowired HexuStore db;
 @Autowired HexuOrderService orders;
 @Autowired HexuFinanceService finance;
 @Autowired HexuWorkflowService workflow;
 @Autowired HexuFulfillment fulfillment;
 final Actor customer=new Actor(201,false,false),owner=new Actor(101,false,false),platform=new Actor(1,true,true);
 final Actor support=new Actor(700,false,true),warehouse=new Actor(701,false,true),otherWarehouse=new Actor(707,false,true),staffOwner=new Actor(708,false,true);

 @BeforeEach void reset(){
  db.jdbc().execute("DROP ALL OBJECTS");
  ResourceDatabasePopulator seed=new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));
  seed.setSqlScriptEncoding("UTF-8");seed.execute(source);HexuTestJson.register(db.jdbc());
  for(Object[] role:new Object[][]{{700,2,"SUPPORT"},{701,2,"WAREHOUSE"},{702,2,"ORDER"},{703,2,"CATALOG"},{704,2,"FINANCE"},{705,2,"OPERATOR"},{707,3,"WAREHOUSE"},{708,2,"OWNER"}})
   db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(?,?,?)",role);
 }
 Map<String,Object> orderBody(int qty){return HexuOrderConsentFixture.accepted(db,2,map("items",List.of(map("id","stapler","qty",qty)),"address",map("name","权限专项收货人","phone","13812345678","region","测试地区","detail","权限专项地址")));}
 String paidOrder(Actor buyer,long shop,int qty){
  String id=str(orders.create(buyer,shop,orderBody(qty)),"id");pay(buyer,id);return id;
 }
 void pay(Actor buyer,String id){Map<String,Object> p=orders.payment(buyer,id);orders.paymentConfirmed(str(p,"id"),str(p,"merchant_no"),number(p,"amount"),"PERMISSION-"+id);}
 String apply(Actor buyer,long shop,String type,int qty){
  String order=paidOrder(buyer,shop,qty);
  long line=number(db.one("select id from hx_order_line where order_id=?",order),"id");
  return str(finance.refundApply(buyer,order,map("lineId",line,"qty",qty,"type",type,"reason","权限专项售后")),"id");
 }
 String ready(String type){String id=apply(customer,2,type,1);finance.refundReview(support,id,map("approve",true));return id;}
 Map<String,Object> refund(String id){return db.one("select * from hx_refund where id=?",id);}
 Map<String,Object> inspection(){return map("receivedQty",1,"goodQty",1,"note","权限专项验收","uploads",Collections.emptyList());}
 Map<String,Object> shipment(){return map("carrier","权限专项物流","tracking","WH12345678");}
 Map<String,Object> row(List<Map<String,Object>> rows,String id){return rows.stream().filter(r->id.equals(str(r,"id"))).findFirst().orElseThrow(AssertionError::new);}
 Map<String,Object> linkedReturn(){
  String id=ready("RETURN");Map<String,Object> body=orderBody(48);body.put("destinationShopId",2);body.put("directShip",true);
  String purchase=str(orders.create(owner,900,body),"id");pay(owner,purchase);
  return fulfillment.linkAftersale(support,2,map("customerRefundId",id,"wholesaleOrderId",purchase));
 }
 void unchanged(String id,Runnable denied){
  Map<String,Object> before=refund(id);long audits=db.jdbc().queryForObject("select count(*) from hx_audit",Long.class);
  assertThrows(BusinessException.class,denied::run);assertEquals(before,refund(id));
  assertEquals(audits,db.jdbc().queryForObject("select count(*) from hx_audit",Long.class));
 }

 @Test void warehouseReadsItsRefundsThroughBothGatesWithExistingFieldsAndStatuses(){
  String returned=ready("RETURN"),exchange=ready("EXCHANGE"),refundOnly=apply(customer,2,"REFUND_ONLY",1);
  String elsewhere=apply(new Actor(401,false,false),3,"RETURN",1);
  for(List<Map<String,Object>> rows:List.of(workflow.managementList(warehouse,2,"refunds"),new HexuReadModels(db).refunds(warehouse,2,true))){
   assertEquals(3,rows.size());assertFalse(rows.stream().anyMatch(r->elsewhere.equals(str(r,"id"))));
   for(String id:List.of(returned,exchange,refundOnly)){
    Map<String,Object> actual=row(rows,id),raw=refund(id);Set<String> fields=new HashSet<>(raw.keySet());fields.add("sku_id");assertEquals(fields,actual.keySet());
    raw.forEach((key,value)->assertEquals(value,actual.get(key),key));assertEquals("stapler",actual.get("sku_id"));assertFalse(actual.containsKey("snapshot_json"));
   }
  }
 }
 @Test void supportOwnersAndPlatformKeepTheirExistingRefundReadAccess(){
  String id=ready("RETURN");
  for(Actor actor:List.of(support,owner,staffOwner,platform)){
   assertEquals(id,workflow.managementList(actor,2,"refunds").get(0).get("id"));
   assertEquals(id,new HexuReadModels(db).refunds(actor,2,true).get(0).get("id"));
  }
 }
 @Test void otherRolesOrdinaryMembersAndUnassignedWorkersCannotReadRefundResources(){
  for(Actor actor:List.of(new Actor(702,false,true),new Actor(703,false,true),new Actor(704,false,true),new Actor(705,false,true),new Actor(706,false,true),customer)){
   assertThrows(BusinessException.class,()->workflow.managementList(actor,2,"refunds"));
   assertThrows(BusinessException.class,()->new HexuReadModels(db).refunds(actor,2,true));
  }
  assertThrows(BusinessException.class,()->workflow.managementList(warehouse,3,"refunds"));
  assertThrows(BusinessException.class,()->new HexuReadModels(db).refunds(otherWarehouse,2,true));
 }
 @Test void warehouseRelatedOrderReadsKeepTheirExistingShopBoundary(){
  String id=ready("RETURN"),order=str(refund(id),"order_id");
  assertEquals(order,orders.detail(warehouse,order,true).get("id"));assertEquals(1,orders.list(warehouse,2,true).size());
  assertThrows(BusinessException.class,()->orders.detail(otherWarehouse,order,true));
  assertThrows(BusinessException.class,()->orders.list(warehouse,3,true));
 }
 @Test void warehouseReadsBothAssociationGetModelsButCannotMutateLinks(){
  Map<String,Object> linked=linkedReturn();String id=str(linked,"customerRefundId");
  assertEquals(owner.id,number(linked,"wholesaleBuyerId"));
  assertEquals(linked,fulfillment.aftersaleLinks(warehouse,2).get(0));
  Map<String,Object> candidate=fulfillment.aftersaleCandidates(warehouse,2,id).get(0);
  assertEquals(linked.get("wholesaleOrderId"),candidate.get("wholesaleOrderId"));
  assertEquals(Set.of("wholesaleOrderId","wholesaleShopId","orderStatus","skuId","availableQty"),candidate.keySet());
  for(String privateField:List.of("address_json","name","phone","snapshot_json","prices"))assertFalse(linked.containsKey(privateField));
  long count=db.jdbc().queryForObject("select count(*) from hx_direct_sale_aftersale",Long.class);
  assertThrows(BusinessException.class,()->fulfillment.linkAftersale(warehouse,2,map("customerRefundId",id,"wholesaleOrderId",linked.get("wholesaleOrderId"))));
  assertEquals(count,db.jdbc().queryForObject("select count(*) from hx_direct_sale_aftersale",Long.class));
 }
 @Test void associationGetModelsRejectOtherRolesAndCrossShopRefundIds(){
  Map<String,Object> linked=linkedReturn();String id=str(linked,"customerRefundId");
  for(Actor actor:List.of(new Actor(702,false,true),new Actor(703,false,true),new Actor(704,false,true),new Actor(705,false,true),new Actor(706,false,true),customer,otherWarehouse)){
   assertThrows(BusinessException.class,()->fulfillment.aftersaleLinks(actor,2));
   assertThrows(BusinessException.class,()->fulfillment.aftersaleCandidates(actor,2,id));
  }
  assertThrows(BusinessException.class,()->fulfillment.aftersaleLinks(warehouse,3));
  assertThrows(BusinessException.class,()->fulfillment.aftersaleCandidates(warehouse,3,id));
  assertThrows(BusinessException.class,()->fulfillment.aftersaleCandidates(otherWarehouse,3,id));
  for(Actor actor:List.of(support,owner,staffOwner,platform)){
   assertEquals(linked,fulfillment.aftersaleLinks(actor,2).get(0));assertEquals(1,fulfillment.aftersaleCandidates(actor,2,id).size());
  }
 }
 @Test void warehouseCanInspectItsAcceptedReturnWithoutConfirmingChannelRefund(){
  String id=ready("RETURN");Map<String,Object> after=finance.inspect(warehouse,id,inspection());
  assertEquals("APPROVED",after.get("status"));assertEquals(1,number(after,"restock_qty"));
  Map<String,Object> evidence=db.parse(after.get("evidence_json"));Map<?,?> saved=(Map<?,?>)evidence.get("inspection");
  assertEquals(warehouse.id,((Number)saved.get("inspector")).longValue());assertEquals("权限专项验收",saved.get("note"));
  assertEquals(0,number(db.one("select refunded from hx_order where id=?",after.get("order_id")),"refunded"));
 }
 @Test void warehouseCanInspectAndShipItsAcceptedExchange(){
  String id=ready("EXCHANGE");assertEquals("WAIT_EXCHANGE",finance.inspect(warehouse,id,inspection()).get("status"));
  Map<String,Object> shipped=finance.exchangeShip(warehouse,id,shipment());assertEquals("EXCHANGE_SHIPPED",shipped.get("status"));
  assertEquals("WH12345678",db.parse(shipped.get("exchange_json")).get("tracking"));
  assertEquals("CLOSED",finance.exchangeReceive(customer,id).get("status"));
 }
 @Test void supportCannotInspectOrShipAndWarehouseCannotPerformCustomerServiceReview(){
  String returned=ready("RETURN"),exchange=ready("EXCHANGE"),pending=apply(customer,2,"RETURN",1);
  unchanged(returned,()->finance.inspect(support,returned,inspection()));
  finance.inspect(warehouse,exchange,inspection());unchanged(exchange,()->finance.exchangeShip(support,exchange,shipment()));
  unchanged(pending,()->finance.refundReview(warehouse,pending,map("approve",true)));
  unchanged(pending,()->finance.refundReview(warehouse,pending,map("approve",false,"reason","不得越权驳回")));
 }
 @Test void warehouseWritesRejectWrongShopOrRoleAndOtherDocumentsRemainPrivate(){
  String returned=ready("RETURN"),exchange=ready("EXCHANGE");finance.inspect(warehouse,exchange,inspection());
  for(Actor actor:List.of(otherWarehouse,new Actor(706,false,true),new Actor(702,false,true),customer)){
   unchanged(returned,()->finance.inspect(actor,returned,inspection()));
   unchanged(exchange,()->finance.exchangeShip(actor,exchange,shipment()));
  }
  for(String kind:List.of("support","agent_application","settlement_account","review","review_append"))
   assertThrows(BusinessException.class,()->workflow.list(warehouse,2,kind,true));
  assertThrows(BusinessException.class,()->workflow.managementList(warehouse,2,"earnings"));
 }
}
