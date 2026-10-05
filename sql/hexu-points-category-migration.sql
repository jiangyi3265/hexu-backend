-- Local integration data: classify the existing point-priced seed items.
-- Preserve any operator-selected category and any published category document.
INSERT INTO hx_sku_detail(shop_id,sku_id,category)
SELECT s.shop_id,s.id,'生活日用'
FROM hx_sku s
LEFT JOIN hx_sku_detail d ON d.shop_id=s.shop_id AND d.sku_id=s.id
WHERE s.id IN ('cup','towel') AND s.shop_id<>900 AND d.sku_id IS NULL;

UPDATE hx_sku_detail
SET category='生活日用'
WHERE sku_id IN ('cup','towel') AND shop_id<>900 AND category='';

INSERT INTO hx_document(id,shop_id,member_id,kind,status,body_json)
SELECT CONCAT('POINT_CATEGORY_',s.id),s.id,0,'category','ACTIVE','{"items":["生活日用","办公用品","家居电器"]}'
FROM hx_shop s
WHERE s.kind<>'WHOLESALE'
AND NOT EXISTS (
  SELECT 1 FROM hx_document d
  WHERE d.shop_id=s.id AND d.kind='category' AND d.status='ACTIVE'
);
