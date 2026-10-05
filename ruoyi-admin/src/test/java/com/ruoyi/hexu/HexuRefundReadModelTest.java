package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import javax.sql.DataSource;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=HexuRefundReadModelTest.Config.class)
class HexuRefundReadModelTest {
 @Configuration static class Config {
  @Bean DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:hexurefundreadtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
  @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
  @Bean ObjectMapper mapper(){return new ObjectMapper();}
  @Bean HexuStore store(JdbcTemplate jdbc,ObjectMapper mapper){return new HexuStore(jdbc,mapper);}
 }
 @Autowired DataSource source;
 @Autowired HexuStore db;
 final Actor customer=new Actor(201,false,false),owner=new Actor(101,false,false);

 @BeforeEach void reset(){
  db.jdbc().execute("DROP ALL OBJECTS");
  ResourceDatabasePopulator seed=new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));
  seed.setSqlScriptEncoding("UTF-8");seed.execute(source);HexuTestJson.register(db.jdbc());
  line(100,"ORDER-PAPER","paper");line(101,"ORDER-PAPER","stapler");
  refund("REF-PAPER","ORDER-PAPER",100,2,201);
 }
 void line(long id,String order,String sku){
  db.jdbc().update("insert into hx_order_line(id,order_id,sku_id,name,qty,unit_price,paid,snapshot_json) values(?,?,?,'历史商品名',1,2990,2990,?)",id,order,sku,db.json(map("prices",Arrays.asList(2990,1800,1400,1000),"privateSnapshot","must-not-be-joined")));
 }
 void refund(String id,String order,long line,long shop,long member){
  db.jdbc().update("insert into hx_refund(id,order_id,line_id,shop_id,member_id,qty,amount,refund_type,status,reason,evidence_json) values(?,?,?,?,?,1,2990,'RETURN','PENDING','专项读模型测试',?)",id,order,line,shop,member,db.json(map("description","现有凭证保留","uploads",Collections.emptyList())));
 }
 @SuppressWarnings("unchecked") List<Map<String,Object>> account(Actor actor,long shop){return (List<Map<String,Object>>)new HexuFinanceService(db).account(actor,shop).get("refunds");}
 Map<String,Object> find(List<Map<String,Object>> rows,String id){return rows.stream().filter(row->id.equals(row.get("id"))).findFirst().orElseThrow(AssertionError::new);}
 void intact(Map<String,Object> row){
  Map<String,Object> raw=db.one("select * from hx_refund where id=?",row.get("id"));
  Set<String> expected=new HashSet<>(raw.keySet());expected.add("sku_id");assertEquals(expected,row.keySet());
  for(Map.Entry<String,Object> field:raw.entrySet())assertEquals(field.getValue(),row.get(field.getKey()),field.getKey());
  for(String privateKey:Arrays.asList("snapshot_json","address_json","phone","prices","unit_price","paid"))assertFalse(row.containsKey(privateKey),privateKey);
 }

 @Test void memberAccountUsesTheRefundedLineSkuNotTheFirstOrderItem(){
  Map<String,Object> row=find(account(customer,2),"REF-PAPER");assertEquals("paper",row.get("sku_id"));intact(row);
 }
 @Test void adminRefundResourceKeepsExistingFieldsAndOnlyAddsSku(){
  Map<String,Object> row=find(new HexuWorkflowService(db).managementList(owner,2,"refunds"),"REF-PAPER");assertEquals("paper",row.get("sku_id"));intact(row);
 }
 @Test void missingAndMismatchedOrderLinesReturnEmptySkuAndKeepRefundRows(){
  refund("REF-MISSING","ORDER-MISSING",999,2,201);
  refund("REF-MISMATCH","ORDER-OTHER",100,2,201);
  for(List<Map<String,Object>> rows:Arrays.asList(account(customer,2),new HexuWorkflowService(db).managementList(owner,2,"refunds"))){
   assertEquals(3,rows.size());
   for(String id:Arrays.asList("REF-MISSING","REF-MISMATCH")){Map<String,Object> row=find(rows,id);assertEquals("",row.get("sku_id"));intact(row);}
  }
 }
 @Test void memberAccountStillSeparatesOtherMembersAndOtherShops(){
  refund("REF-OTHER-MEMBER","ORDER-PAPER",100,2,202);refund("REF-OTHER-SHOP","ORDER-PAPER",100,3,201);
  assertEquals(1,account(customer,2).size());assertEquals("REF-PAPER",account(customer,2).get(0).get("id"));
  assertEquals("REF-OTHER-MEMBER",account(new Actor(202,false,false),2).get(0).get("id"));
  assertThrows(BusinessException.class,()->account(new Actor(700,false,true),2));
  assertEquals(2,new HexuWorkflowService(db).managementList(owner,2,"refunds").size());
 }
 @Test void adminRefundsKeepSupportRoleAndShopAuthorization(){
  db.jdbc().update("insert into hx_staff values(700,2,'SUPPORT')");db.jdbc().update("insert into hx_staff values(701,2,'WAREHOUSE')");
  HexuWorkflowService workflows=new HexuWorkflowService(db);
  assertEquals("paper",find(workflows.managementList(new Actor(700,false,true),2,"refunds"),"REF-PAPER").get("sku_id"));
  assertThrows(BusinessException.class,()->workflows.managementList(new Actor(700,false,true),3,"refunds"));
  assertEquals("paper",find(workflows.managementList(new Actor(701,false,true),2,"refunds"),"REF-PAPER").get("sku_id"));
  assertThrows(BusinessException.class,()->workflows.managementList(new Actor(701,false,true),3,"refunds"));
  assertThrows(BusinessException.class,()->workflows.managementList(customer,2,"refunds"));
 }
 @Test void historicalLineSkuDoesNotDependOnCurrentCatalogAvailability(){
  db.jdbc().update("delete from hx_sku where shop_id=2 and id='paper'");
  assertEquals("paper",find(account(customer,2),"REF-PAPER").get("sku_id"));
  assertEquals("paper",find(new HexuWorkflowService(db).managementList(owner,2,"refunds"),"REF-PAPER").get("sku_id"));
 }
}
