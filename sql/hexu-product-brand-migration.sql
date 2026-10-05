-- Add optional searchable SKU brand without changing existing products. Safe to rerun.
SET @hexu_brand_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='hx_sku_detail' AND column_name='brand');
SET @hexu_brand_sql = IF(@hexu_brand_exists=0, 'ALTER TABLE hx_sku_detail ADD COLUMN brand VARCHAR(80) NOT NULL DEFAULT '''' AFTER category', 'SELECT 1');
PREPARE hexu_brand_statement FROM @hexu_brand_sql;
EXECUTE hexu_brand_statement;
DEALLOCATE PREPARE hexu_brand_statement;
