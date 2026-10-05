package com.ruoyi.hexu;

import static com.ruoyi.hexu.HexuStore.*;

import java.util.Map;

/** 隔离数据库中的合成购买协议；旧正向订单测试仍须显式传当前版本。 */
final class HexuOrderConsentFixture {
  private static final String TYPE = "PURCHASE_AGREEMENT";
  private static final String ID = "POL-TEST-PURCHASE";
  private static final String VERSION = "E2E-SANDBOX-20261004";

  private HexuOrderConsentFixture() {}

  static Map<String,Object> accepted(HexuStore db, long shop, Map<String,Object> body) {
    if (db.optional("select id from hx_policy where id=?", ID) == null)
      db.jdbc().update("insert into hx_policy(id,shop_id,policy_type,version,title,content,status)"
          + " values(?,0,?,?,?,?,'PUBLISHED')", ID, TYPE, VERSION,
          "本地测试购买协议（非正式文件）", "仅用于隔离内存数据库的自动化测试，不用于正式环境或真实交易。");
    Map<String,Object> current = (Map<String,Object>)new HexuPolicies(db).current(shop).get(TYPE);
    body.put("orderConsent", true);
    body.put("agreementPolicyId", current.get("id"));
    body.put("agreementVersion", current.get("version"));
    return body;
  }
}
