-- Align published page configuration with the current mini-program form keys.
-- Only the obsolete labels and example G06 defaults are changed; all other
-- merchant-configured page content remains untouched. Safe to rerun.
START TRANSACTION;

UPDATE hx_document
SET body_json = JSON_SET(
        body_json,
        '$.screens.G27.blocks[2].items[0].label', '实际收货',
        '$.screens.G27.blocks[2].items[1].label', '合格回库'
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE kind = 'page_schema'
  AND status = 'ACTIVE'
  AND JSON_UNQUOTE(JSON_EXTRACT(body_json, '$.screens.G27.blocks[2].items[0].label')) = '退回数量'
  AND JSON_UNQUOTE(JSON_EXTRACT(body_json, '$.screens.G27.blocks[2].items[1].label')) = '实际验收';

UPDATE hx_document
SET body_json = JSON_SET(
        body_json,
        '$.screens.G06.blocks[2].items[0].value', '',
        '$.screens.G06.blocks[2].items[1].value', '',
        '$.screens.G06.blocks[2].items[2].value', ''
    ),
    updated_at = CURRENT_TIMESTAMP
WHERE kind = 'page_schema'
  AND status = 'ACTIVE'
  AND JSON_UNQUOTE(JSON_EXTRACT(body_json, '$.screens.G06.blocks[2].items[0].value')) = '安溪青木商城'
  AND JSON_UNQUOTE(JSON_EXTRACT(body_json, '$.screens.G06.blocks[2].items[1].value')) = '李四';

COMMIT;
