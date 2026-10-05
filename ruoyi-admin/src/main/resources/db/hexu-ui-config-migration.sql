-- Run once after hexu-schema.sql/hexu-seed.sql.  Existing ACTIVE rows are
-- preserved; these defaults only fill tenants that do not have decoration.
ALTER TABLE hx_document MODIFY body_json MEDIUMTEXT NOT NULL;
INSERT INTO hx_document(id,shop_id,member_id,kind,status,body_json)
SELECT 'DECORATION1',1,0,'decoration','ACTIVE','{"name":"公司直营商城","brandName":"禾序商贸","badge":"公司直营","slogan":"正品好货 · 品质生活","searchPlaceholder":"搜索商品、品牌、生活好物","banners":[{"image":"hero","title":"自然之选\\n让生活更美好","subtitle":"精选家居好物","buttonText":"立即选购","target":"M04","sort":1,"enabled":true}],"categories":[{"name":"居家生活","icon":"home","target":"M04","sort":1,"enabled":true},{"name":"美食饮品","icon":"gift","target":"M04","sort":2,"enabled":true},{"name":"个护清洁","icon":"leaf","target":"M04","sort":3,"enabled":true},{"name":"数码家电","icon":"grid","target":"M04","sort":4,"enabled":true},{"name":"运动户外","icon":"bag","target":"M04","sort":5,"enabled":true}],"homeProducts":["cup","sofa","paper","oil"]}'
WHERE NOT EXISTS (SELECT 1 FROM hx_document WHERE shop_id=1 AND kind='decoration' AND status='ACTIVE')
  AND NOT EXISTS (SELECT 1 FROM hx_document WHERE id='DECORATION1');
INSERT INTO hx_document(id,shop_id,member_id,kind,status,body_json)
SELECT 'DECORATION2',2,0,'decoration','ACTIVE','{"name":"安溪禾序商城","brandName":"禾序商贸","slogan":"安溪好物 · 品质生活在身边","dealerSearchPlaceholder":"搜索本店商品","banners":[{"image":"tea","title":"安吉好物\\n品质生活在身边","subtitle":"精选日用百货 · 服务本地家庭","buttonText":"领券下单","target":"M55","sort":1,"enabled":true}],"categories":[{"name":"领券中心","icon":"coupon","target":"M55","sort":1,"enabled":true},{"name":"新品上架","icon":"leaf","target":"M04","sort":2,"enabled":true},{"name":"热销推荐","icon":"star","target":"M56","sort":3,"enabled":true},{"name":"生活家居","icon":"home","target":"M04","sort":4,"enabled":true},{"name":"个人护理","icon":"bag","target":"M04","sort":5,"enabled":true}],"homeProducts":["paper","oil","cup","towel"]}'
WHERE NOT EXISTS (SELECT 1 FROM hx_document WHERE shop_id=2 AND kind='decoration' AND status='ACTIVE')
  AND NOT EXISTS (SELECT 1 FROM hx_document WHERE id='DECORATION2');
