-- Add only the buyer remark column, preserving all existing orders. Safe to rerun.
SET @hexu_remark_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='hx_order' AND column_name='remark');
SET @hexu_remark_sql = IF(@hexu_remark_exists=0, 'ALTER TABLE hx_order ADD COLUMN remark VARCHAR(800) NOT NULL DEFAULT ''''', 'SELECT 1');
PREPARE hexu_remark_statement FROM @hexu_remark_sql;
EXECUTE hexu_remark_statement;
DEALLOCATE PREPARE hexu_remark_statement;
