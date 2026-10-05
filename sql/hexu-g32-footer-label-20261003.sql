-- The management page opens the migration review list; it does not submit an application.
-- Update only the obsolete published button label, preserving all other tenant settings.
UPDATE hx_document
SET body_json = JSON_SET(body_json, '$.screens.G32.footer.label', '查看改绑申请'),
    updated_at = CURRENT_TIMESTAMP
WHERE kind = 'page_schema'
  AND status = 'ACTIVE'
  AND JSON_UNQUOTE(JSON_EXTRACT(body_json, '$.screens.G32.footer.label')) = '申请更改归属';
