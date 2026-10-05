package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HexuPolicies {
  // 购买协议独立于登录协议和代理合同，正式环境须由平台发布正文与版本。
  private static final Set<String> TYPES =
      new HashSet<>(Arrays.asList("USER_AGREEMENT", "PRIVACY_POLICY", "THIRD_PARTY_SHARING", "AGENT_AGREEMENT", "PURCHASE_AGREEMENT", "CUSTOMER_AUTHORIZATION", "WITHDRAWAL_AGREEMENT"));
  private static final List<String> LOGIN_TYPES =
      Arrays.asList("USER_AGREEMENT", "PRIVACY_POLICY", "THIRD_PARTY_SHARING");
  private final HexuStore db;
  public HexuPolicies(HexuStore db) {
    this.db = db;
  }

  public Map<String, Object> current(long shop) {
    db.one("select id from hx_shop where id=?", shop);
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map<String, Object> row :
        db.jdbc()
            .queryForList(
                "select id,shop_id,policy_type,version,title,content,published_at from hx_policy"
                    + " where shop_id in (0,?) and status='PUBLISHED' order by shop_id,"
                    + " published_at, id",
                shop)) {
      String type = str(row, "policy_type");
      result.put(
          type,
          map(
              "id",
              row.get("id"),
              "shopId",
              row.get("shop_id"),
              "type",
              type,
              "version",
              row.get("version"),
              "title",
              row.get("title"),
              "content",
              row.get("content"),
              "publishedAt",
              row.get("published_at")));
    }
    return result;
  }

  public List<Map<String, Object>> list(Actor actor, long shop) {
    db.manage(actor, shop, "OWNER");
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row :
        db.jdbc()
            .queryForList(
                "select * from hx_policy where shop_id in (0,?) order by published_at desc,id desc",
                shop))
      result.add(
          map(
              "id",
              row.get("id"),
              "shopId",
              row.get("shop_id"),
              "type",
              row.get("policy_type"),
              "version",
              row.get("version"),
              "title",
              row.get("title"),
              "content",
              row.get("content"),
              "status",
              row.get("status"),
              "publishedAt",
              row.get("published_at")));
    return result;
  }

  @Transactional
  public Map<String, Object> publish(Actor actor, long shop, Map<String, Object> body) {
    db.platform(actor);
    String type = str(body, "type"),
        version = str(body, "version").trim(),
        title = str(body, "title").trim(),
        content = str(body, "content").trim();
    require(
        TYPES.contains(type)
            && version.matches("[A-Za-z0-9._-]{1,40}")
            && title.length() >= 2
            && title.length() <= 160
            && content.length() >= 20
            && content.length() <= 100000,
        "协议类型、版本、标题或正文无效");
    require(shop == 0 || db.optional("select id from hx_shop where id=?", shop) != null, "商城不存在");
    require(
        db.optional(
                "select id from hx_policy where shop_id=? and policy_type=? and version=?",
                shop,
                type,
                version)
            == null,
        "协议版本已存在，已发布正文不可覆盖");
    String id = id("POL");
    db.jdbc()
        .update(
            "update hx_policy set status='SUPERSEDED' where shop_id=? and policy_type=? and"
                + " status='PUBLISHED'",
            shop,
            type);
    db.jdbc()
        .update(
            "insert into hx_policy(id,shop_id,policy_type,version,title,content,status)"
                + " values(?,?,?,?,?,?,'PUBLISHED')",
            id,
            shop,
            type,
            version,
            title,
            content);
    db.audit(actor, shop, "POLICY_PUBLISH", id, null, map("type", type, "version", version));
    return map(
        "id",
        id,
        "shopId",
        shop,
        "type",
        type,
        "version",
        version,
        "title",
        title,
        "content",
        content,
        "status",
        "PUBLISHED");
  }

  @SuppressWarnings("unchecked")
  @Transactional
  public List<Map<String, Object>> consent(
      Actor actor, long shop, Map<String, Object> body, String source) {
    db.member(actor);
    require(body.get("versions") instanceof Map, "请提交协议版本");
    Map<String, Object> versions = (Map<String, Object>) body.get("versions");
    Map<String, Object> active = current(shop);
    for (String type : LOGIN_TYPES) {
      if (!active.containsKey(type)) continue;
      Map<String, Object> policy = (Map<String, Object>) active.get(type);
      require(str(versions, type).equals(str(policy, "version")), "协议已更新，请阅读最新版本后重新确认");
    }
    require(
        active.containsKey("USER_AGREEMENT") && active.containsKey("PRIVACY_POLICY"),
        "用户协议或隐私政策正文尚未发布");
    for (String type : LOGIN_TYPES) {
      if (!active.containsKey(type)) continue;
      String version = str(versions, type);
      if (db.optional(
              "select member_id from hx_policy_consent where member_id=? and shop_id=? and"
                  + " policy_type=? and version=?",
              actor.id,
              shop,
              type,
              version)
          == null)
        db.jdbc()
            .update(
                "insert into hx_policy_consent(member_id,shop_id,policy_type,version,source)"
                    + " values(?,?,?,?,?)",
                actor.id,
                shop,
                type,
                version,
                source);
    }
    db.audit(
        actor,
        shop,
        "POLICY_CONSENT",
        String.valueOf(actor.id),
        null,
        map("versions", versions, "source", source));
    return consents(actor, shop);
  }

  public List<Map<String, Object>> consents(Actor actor, long shop) {
    db.member(actor);
    return consentRows("where member_id=? and shop_id=?", actor.id, shop);
  }

  public List<Map<String, Object>> audit(Actor actor, long shop) {
    db.manage(actor, shop, "OWNER");
    return consentRows("where shop_id=? order by consented_at desc limit 500", shop);
  }

  private List<Map<String, Object>> consentRows(String condition, Object... args) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row :
        db.jdbc().queryForList("select * from hx_policy_consent " + condition, args))
      result.add(
          map(
              "memberId",
              row.get("member_id"),
              "shopId",
              row.get("shop_id"),
              "type",
              row.get("policy_type"),
              "version",
              row.get("version"),
              "source",
              row.get("source"),
              "consentedAt",
              row.get("consented_at")));
    return result;
  }
}
