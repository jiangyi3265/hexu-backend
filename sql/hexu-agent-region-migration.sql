-- Apply once to an existing Hexu database before deploying the agent directory UI.
CREATE TABLE IF NOT EXISTS hx_agent_region (
  agent_id BIGINT NOT NULL,
  shop_id BIGINT NOT NULL,
  region_path VARCHAR(100) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(agent_id,region_path),
  INDEX idx_hx_agent_region_shop_path(shop_id,region_path,agent_id)
);
CREATE INDEX idx_hx_agent_shop_parent ON hx_agent(shop_id,parent_id,id);
