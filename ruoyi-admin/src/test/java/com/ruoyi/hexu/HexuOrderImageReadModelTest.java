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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.ResponseEntity;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=HexuOrderImageReadModelTest.Config.class)
class HexuOrderImageReadModelTest {
 @Configuration static class Config {
  @Bean DataSource source(){return new DriverManagerDataSource("jdbc:h2:mem:hexuorderimagetest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");}
  @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
  @Bean ObjectMapper mapper(){return new ObjectMapper();}
  @Bean HexuStore store(JdbcTemplate jdbc,ObjectMapper mapper){return new HexuStore(jdbc,mapper);}
 }
 @Autowired DataSource source;
 @Autowired HexuStore db;
 final Actor customer=new Actor(201,false,false),owner=new Actor(101,false,false);
 HexuOrderService orders;
 String order;

 @BeforeEach void reset(){
  db.jdbc().execute("DROP ALL OBJECTS");
  ResourceDatabasePopulator seed=new ResourceDatabasePopulator(new ClassPathResource("db/hexu-schema.sql"),new ClassPathResource("db/hexu-seed.sql"));
  seed.setSqlScriptEncoding("UTF-8");seed.execute(source);HexuTestJson.register(db.jdbc());
  orders=new HexuOrderService(db,new HexuCatalogService(db));
   order=newOrder();
  }
  String newOrder(){return str(orders.create(customer,2,HexuOrderConsentFixture.accepted(db,2,map("items",Collections.singletonList(map("id","paper","qty",1)),"address",map("name","专项测试","detail","本地测试地址")))),"id");}
  @SuppressWarnings("unchecked") Map<String,Object> first(Map<String,Object> order){return ((List<Map<String,Object>>)order.get("items")).get(0);}
  Map<String,Object> listed(Actor actor,boolean management){return orders.list(actor,2,management).stream().filter(row->order.equals(row.get("id"))).findFirst().orElseThrow();}
  List<Map<String,Object>> variants(){return Arrays.asList(first(orders.detail(customer,order,false)),first(orders.detail(owner,order,true)),first(listed(customer,false)),first(listed(owner,true)));}
 String attachment(String character,long shop,String purpose){
  String id="FILE"+String.join("",Collections.nCopies(32,character));
  db.jdbc().update("insert into hx_attachment(id,shop_id,member_id,purpose,mime,iv,cipher_bytes) values(?,?,201,?,'image/png',?,?)",id,shop,purpose,new byte[12],new byte[]{1});return id;
 }
 void asset(String raw){db.jdbc().update("update hx_sku set asset=? where shop_id=2 and id='paper'",raw);}
  void gallery(String sku,String file){
  if(db.optional("select sku_id from hx_sku_detail where shop_id=2 and sku_id=?",sku)==null)db.jdbc().update("insert into hx_sku_detail(shop_id,sku_id) values(2,?)",sku);
  db.jdbc().update("update hx_sku_detail set gallery_json=? where shop_id=2 and sku_id=?",db.json(map("items",Collections.singletonList(file))),sku);
  }
  String privateCover(){return "/hexu/app/attachments/order-cover/"+order+"/"+db.one("select id from hx_order_line where order_id=?",order).get("id");}
  void expectAsset(String expected){for(Map<String,Object> line:variants()){assertEquals(expected,line.get("asset"));assertEquals("原木清香 / 6包",line.get("spec"));assertEquals("paper",line.get("sku_id"));assertEquals(1,number(line,"qty"));assertEquals(2990,number(line,"unit_price"));}}

 @Test void detailAndListResolveTheActualOrderShopNotSameSkuInAnotherShop(){
  db.jdbc().update("update hx_sku set asset='stapler',spec='其他店规格' where shop_id=3 and id='paper'");
  expectAsset("/hexu/app/ui-assets/paper");
  Map<String,Object> member=first(orders.detail(customer,order,false));
  assertEquals("清风 抽取式面巾纸",member.get("name"));assertFalse(member.containsKey("snapshot_json"));
  for(String secret:Arrays.asList("available","locked","defective","in_transit","cloud_price","center_price","owner_price","retail_min","retail_max"))assertFalse(member.containsKey(secret),secret);
  assertThrows(BusinessException.class,()->orders.detail(new Actor(202,false,false),order,false));
  assertThrows(BusinessException.class,()->orders.detail(new Actor(401,false,false),order,true));
 }
 @SuppressWarnings("unchecked") @Test void managementDetailReturnsTrueSnapshotOnlyWithinRequestedShop(){
  HexuIdentity identity=new HexuIdentity(db);
  MockHttpServletRequest request=new MockHttpServletRequest();
  request.addHeader("Authorization","Bearer "+identity.session(101));
  HexuAppController controller=new HexuAppController(identity,db,null,orders,null,null,null,null,null,null);
  Map<String,Object> result=(Map<String,Object>)controller.managementOrder(request,order,2);
  Map<String,Object> detail=(Map<String,Object>)result.get("data");
  assertEquals(order,detail.get("id"));
  assertEquals("林小禾",detail.get("buyerName"));
  assertNotNull(detail.get("ruleEffectiveAt"));
  assertTrue(first(detail).containsKey("snapshot_json"));
  assertThrows(BusinessException.class,()->controller.managementOrder(request,order,3));
 }
  @Test void canonicalPublicCropPathDoesNotUseCurrentStorefrontAliases(){
   asset("/hexu/app/ui-assets/paper");order=newOrder();expectAsset("/hexu/app/ui-assets/paper");
  }
  @Test void publishedProductAttachmentHasOnlyItsPublicProductUrl(){
   String file=attachment("a",2,"PRODUCT");asset(file);gallery("paper",file);order=newOrder();
   expectAsset("/hexu/app/attachments/product/"+file);
   asset("/hexu/app/attachments/product/"+file);expectAsset("/hexu/app/attachments/product/"+file);
   asset("stapler");expectAsset("/hexu/app/attachments/product/"+file);
   gallery("paper","not-published");expectAsset(privateCover());
  }
  @Test void privateOrCrossShopAttachmentNeverAppearsAsOrderAsset(){
  for(String purpose:Arrays.asList("IDENTITY","FINANCE","RETURN","REVIEW","STOREFRONT","AVATAR")){
   String character=String.valueOf((char)('a'+Arrays.asList("IDENTITY","FINANCE","RETURN","REVIEW","STOREFRONT","AVATAR").indexOf(purpose)));
    String file=attachment(character,2,purpose);asset(file);gallery("paper",file);order=newOrder();expectAsset("");
   }
   String foreign=attachment("0",3,"PRODUCT");asset(foreign);gallery("paper",foreign);order=newOrder();expectAsset("");
  }
  @Test void snapshotCannotBypassPrivateOrCrossShopProductImageGate(){
   String foreign=attachment("d",3,"PRODUCT"),identity=attachment("e",2,"IDENTITY");
   for(String file:Arrays.asList(foreign,identity)){
    Map<String,Object> snapshot=db.parse(db.one("select snapshot_json from hx_order_line where order_id=?",order).get("snapshot_json"));
    snapshot.put("asset","/hexu/app/attachments/product/"+file);
    db.jdbc().update("update hx_order_line set snapshot_json=? where order_id=?",db.json(snapshot),order);
    for(Map<String,Object> line:variants())assertEquals("",line.get("asset"));
   }
  }
  @Test void inactiveHistoricalFileKeepsTheExistingPublicPublicationGate(){
   String file=attachment("b",2,"PRODUCT");asset(file);gallery("paper",file);order=newOrder();expectAsset("/hexu/app/attachments/product/"+file);
   db.jdbc().update("update hx_sku set status='INACTIVE' where shop_id=2 and id='paper'");expectAsset(privateCover());
  // The existing public route allows a file still published by another active SKU in the same shop.
  gallery("stapler",file);expectAsset("/hexu/app/attachments/product/"+file);
   db.jdbc().update("update hx_shop set status='INACTIVE' where id=2");expectAsset(privateCover());
 }
 @Test void arbitraryUrlsPrivateRoutesAndUnknownAssetsRemainEmpty(){
  for(String raw:Arrays.asList("https://example.test/private.jpg","/hexu/app/attachments/FILEaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","/hexu/app/attachments/avatar/FILEaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","not-a-public-crop","")){
    asset(raw);order=newOrder();expectAsset("");
   }
  }
  @Test void deletedProductKeepsOrderTimeSpecAndPublicUiAsset(){
   db.jdbc().update("delete from hx_sku where shop_id=2 and id='paper'");
   for(Map<String,Object> line:variants()){assertEquals("/hexu/app/ui-assets/paper",line.get("asset"));assertEquals("原木清香 / 6包",line.get("spec"));assertEquals("清风 抽取式面巾纸",line.get("name"));assertEquals(2990,number(line,"unit_price"));}
  }
  @Test void currentCatalogPresentationNeverOverwritesHistoricalNameSpecAssetOrPaidPrice(){
   db.jdbc().update("update hx_sku set name='当前商品新名称',retail=3990,spec='本店当前规格',asset='stapler' where shop_id=2 and id='paper'");
   Map<String,Object> line=first(orders.detail(customer,order,false));
   assertEquals("清风 抽取式面巾纸",line.get("name"));assertEquals(2990,number(line,"unit_price"));assertEquals(2990,number(line,"paid"));
   assertEquals("原木清香 / 6包",line.get("spec"));assertEquals("/hexu/app/ui-assets/paper",line.get("asset"));
   for(Map<String,Object> variant:variants()){assertEquals("原木清香 / 6包",variant.get("spec"));assertEquals("/hexu/app/ui-assets/paper",variant.get("asset"));}
   Map<String,Object> snapshot=db.parse(db.one("select snapshot_json from hx_order_line where order_id=?",order).get("snapshot_json"));
   assertEquals("原木清香 / 6包",snapshot.get("spec"));assertEquals("/hexu/app/ui-assets/paper",snapshot.get("asset"));
  }
  @Test void legacyOrderWithoutPresentationSnapshotUsesOnlyCurrentShopPublicFields(){
   Map<String,Object> snapshot=db.parse(db.one("select snapshot_json from hx_order_line where order_id=?",order).get("snapshot_json"));
   snapshot.remove("spec");snapshot.remove("asset");
   db.jdbc().update("update hx_order_line set snapshot_json=? where order_id=?",db.json(snapshot),order);
   db.jdbc().update("update hx_sku set spec='旧订单安全回退规格',asset='stapler' where shop_id=2 and id='paper'");
   db.jdbc().update("update hx_sku set spec='其他商城不能回退',asset='cup' where shop_id=3 and id='paper'");
   for(Map<String,Object> line:variants()){assertEquals("旧订单安全回退规格",line.get("spec"));assertEquals("/hexu/app/ui-assets/stapler",line.get("asset"));}
   String privateFile=attachment("c",2,"IDENTITY");asset(privateFile);
   for(Map<String,Object> line:variants())assertEquals("",line.get("asset"));
   db.jdbc().update("delete from hx_sku where shop_id=2 and id='paper'");
   for(Map<String,Object> line:variants()){assertEquals("",line.get("asset"));assertEquals("",line.get("spec"));}
  }
  @Test void pointsRedemptionAlsoCapturesOrderTimePresentation(){
   String redemption=str(orders.redeem(customer,2,map("skuId","towel","qty",1,"scope",0,"address",map("name","兑换用户","detail","本地测试地址"))),"id");
   Map<String,Object> snapshot=db.parse(db.one("select snapshot_json from hx_order_line where order_id=?",redemption).get("snapshot_json"));
   Map<String,Object> sku=db.one("select spec,asset from hx_sku where shop_id=2 and id='towel'");
   assertEquals(sku.get("spec"),snapshot.get("spec"));assertEquals("/hexu/app/ui-assets/towel",snapshot.get("asset"));
   db.jdbc().update("update hx_sku set spec='新规格',asset='stapler' where shop_id=2 and id='towel'");
   Map<String,Object> line=first(orders.detail(customer,redemption,false));
   assertEquals(snapshot.get("spec"),line.get("spec"));assertEquals(snapshot.get("asset"),line.get("asset"));
  }
  @SuppressWarnings("unchecked") @Test void historicalCoverRequiresOrderAccessAndKeepsPublicGateClosed()throws Exception{
   BufferedImage image=new BufferedImage(3,3,BufferedImage.TYPE_INT_RGB);
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
   HexuIdentity uploaderIdentity=new HexuIdentity(db){@Override public Actor admin(){return owner;}};
   HexuAttachmentController uploader=new HexuAttachmentController(uploaderIdentity,db);
   ReflectionTestUtils.setField(uploader,"secret","order-cover-test-secret-at-least-32-characters");
   MockMultipartFile imageFile=new MockMultipartFile("file","historical.png","image/png",bytes.toByteArray());
   String file=str((Map<String,Object>)((Map<?,?>)uploader.upload(new MockHttpServletRequest(),2,"PRODUCT",imageFile)).get("data"),"id");
   asset(file);gallery("paper",file);order=newOrder();String otherOrder=newOrder();
   long lineId=number(db.one("select id from hx_order_line where order_id=?",order),"id");
   long otherLineId=number(db.one("select id from hx_order_line where order_id=?",otherOrder),"id");
   HexuIdentity identity=new HexuIdentity(db);HexuAttachmentController reader=new HexuAttachmentController(identity,db);
   ReflectionTestUtils.setField(reader,"secret","order-cover-test-secret-at-least-32-characters");
   MockHttpServletRequest buyer=new MockHttpServletRequest();buyer.addHeader("Authorization","Bearer "+identity.session(201));
   assertEquals("/hexu/app/attachments/product/"+file,first(orders.detail(customer,order,false)).get("asset"));
   db.jdbc().update("update hx_sku set status='INACTIVE' where shop_id=2 and id='paper'");
   assertThrows(BusinessException.class,()->reader.publishedProduct(file));expectAsset(privateCover());
   ResponseEntity<byte[]> result=reader.orderCover(buyer,order,lineId);
   assertArrayEquals(bytes.toByteArray(),result.getBody());assertEquals("image/png",result.getHeaders().getContentType().toString());
   assertEquals("no-store",result.getHeaders().getCacheControl());assertEquals("nosniff",result.getHeaders().getFirst("X-Content-Type-Options"));
   assertThrows(BusinessException.class,()->reader.orderCover(buyer,order,otherLineId));
   MockHttpServletRequest expired=new MockHttpServletRequest();expired.addHeader("Authorization","Bearer hx_invalid");
   assertThrows(HexuMemberAuthException.class,()->reader.orderCover(expired,order,lineId));
   MockHttpServletRequest stranger=new MockHttpServletRequest();stranger.addHeader("Authorization","Bearer "+identity.session(202));
   assertThrows(BusinessException.class,()->reader.orderCover(stranger,order,lineId));
   MockHttpServletRequest ownerRequest=new MockHttpServletRequest();ownerRequest.addHeader("Authorization","Bearer "+identity.session(101));
   assertArrayEquals(bytes.toByteArray(),reader.orderCover(ownerRequest,order,lineId).getBody());
   db.jdbc().update("insert into hx_staff(user_id,shop_id,role_code) values(700,2,'ORDER'),(701,3,'ORDER')");
   for(long staffId:new long[]{700,701}){
    HexuIdentity staffIdentity=new HexuIdentity(db){@Override public Actor admin(){return new Actor(staffId,false,true);}};
    HexuAttachmentController staffReader=new HexuAttachmentController(staffIdentity,db);
    ReflectionTestUtils.setField(staffReader,"secret","order-cover-test-secret-at-least-32-characters");
    if(staffId==700)assertArrayEquals(bytes.toByteArray(),staffReader.orderCover(new MockHttpServletRequest(),order,lineId).getBody());
    else assertThrows(BusinessException.class,()->staffReader.orderCover(new MockHttpServletRequest(),order,lineId));
   }
   db.jdbc().update("update hx_staff set role_code='CATALOG' where user_id=700 and shop_id=2");
   HexuIdentity catalogIdentity=new HexuIdentity(db){@Override public Actor admin(){return new Actor(700,false,true);}};
   assertThrows(BusinessException.class,()->new HexuAttachmentController(catalogIdentity,db).orderCover(new MockHttpServletRequest(),order,lineId));
   HexuIdentity platformIdentity=new HexuIdentity(db){@Override public Actor admin(){return new Actor(1,true,true);}};
   HexuAttachmentController platformReader=new HexuAttachmentController(platformIdentity,db);
   ReflectionTestUtils.setField(platformReader,"secret","order-cover-test-secret-at-least-32-characters");
   assertArrayEquals(bytes.toByteArray(),platformReader.orderCover(new MockHttpServletRequest(),order,lineId).getBody());
   db.jdbc().update("update hx_order set status='CANCELLED' where id=?",order);
   db.jdbc().update("update hx_shop set status='INACTIVE' where id=2");
   assertArrayEquals(bytes.toByteArray(),reader.orderCover(buyer,order,lineId).getBody());
  }
  @Test void historicalCoverRejectsLegacyAndWrongScopeSnapshots()throws Exception{
   HexuIdentity identity=new HexuIdentity(db);HexuAttachmentController reader=new HexuAttachmentController(identity,db);
   MockHttpServletRequest buyer=new MockHttpServletRequest();buyer.addHeader("Authorization","Bearer "+identity.session(201));
   long lineId=number(db.one("select id from hx_order_line where order_id=?",order),"id");
   Map<String,Object> snapshot=db.parse(db.one("select snapshot_json from hx_order_line where id=?",lineId).get("snapshot_json"));
   snapshot.remove("asset");db.jdbc().update("update hx_order_line set snapshot_json=? where id=?",db.json(snapshot),lineId);
   assertThrows(BusinessException.class,()->reader.orderCover(buyer,order,lineId));
   String foreign=attachment("f",3,"PRODUCT"),privateFile=attachment("1",2,"IDENTITY");
   for(String file:Arrays.asList(foreign,privateFile)){
    snapshot.put("asset","/hexu/app/attachments/product/"+file);
    db.jdbc().update("update hx_order_line set snapshot_json=? where id=?",db.json(snapshot),lineId);
    assertEquals("",first(orders.detail(customer,order,false)).get("asset"));
    assertThrows(BusinessException.class,()->reader.orderCover(buyer,order,lineId));
   }
  }
}
