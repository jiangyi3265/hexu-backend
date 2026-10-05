-- Keep a recoverable copy of each active page schema before changing M14.
CREATE TABLE IF NOT EXISTS hexu_integration.hx_migration_backup_m14_20260928 (
  document_id VARCHAR(64) PRIMARY KEY,
  shop_id BIGINT NOT NULL,
  body_json LONGTEXT NOT NULL,
  backed_up_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Also repair a table left by an interrupted first execution.
ALTER TABLE hexu_integration.hx_migration_backup_m14_20260928
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

START TRANSACTION;

INSERT IGNORE INTO hexu_integration.hx_migration_backup_m14_20260928 (document_id, shop_id, body_json)
SELECT id, shop_id, body_json
FROM hexu_integration.hx_document
WHERE kind = 'page_schema' AND status = 'ACTIVE'
  AND JSON_UNQUOTE(JSON_EXTRACT(body_json, '$.screens.M14.blocks[0].items[2].kind')) = 'select'
  AND JSON_UNQUOTE(JSON_EXTRACT(body_json, '$.screens.M14.blocks[0].items[4].key')) = '地址用途';

UPDATE hexu_integration.hx_document AS document
JOIN hexu_integration.hx_migration_backup_m14_20260928 AS backup ON backup.document_id = document.id
SET document.body_json = JSON_SET(
  JSON_REMOVE(document.body_json, '$.screens.M14.blocks[0].items[2].options'),
  '$.screens.M14.blocks[0].items[2].kind', 'addressRegion',
  '$.screens.M14.blocks[0].items[2].value', '',
  '$.screens.M14.blocks[0].items[2].required', JSON_EXTRACT('true', '$'),
  '$.screens.M14.blocks[0].items[4].key', 'usage'
), document.updated_at = CURRENT_TIMESTAMP
WHERE document.kind = 'page_schema' AND document.status = 'ACTIVE'
  AND JSON_UNQUOTE(JSON_EXTRACT(document.body_json, '$.screens.M14.blocks[0].items[2].kind')) = 'select'
  AND JSON_UNQUOTE(JSON_EXTRACT(document.body_json, '$.screens.M14.blocks[0].items[4].key')) = '地址用途';

COMMIT;
