package com.ruoyi.hexu;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

@Service
public class HexuCommands {
    private final HexuStore db;

    public HexuCommands(HexuStore db) {
        this.db = db;
    }

    @Transactional
    public Object execute(Actor actor, String key, String operation, Map<String,Object> body,
                          Supplier<Object> action) {
        require(key != null && key.matches("[A-Za-z0-9_-]{8,100}"),
                "写操作须携带8至100位Idempotency-Key");
        db.one(actor.staff ? "select user_id from sys_user where user_id=? for update"
                : "select id from hx_member where id=? for update", actor.id);
        String hash = DigestUtils.md5DigestAsHex(
                (operation + db.json(body)).getBytes(StandardCharsets.UTF_8));
        Map<String,Object> prior = db.optional(
                "select * from hx_idempotency where actor_key=? and request_key=?",
                actor.key(), key);
        if (prior != null) {
            require(operation.equals(str(prior, "operation"))
                    && hash.equals(str(prior, "request_hash")), "幂等键已用于不同请求");
            Object result = db.parse(prior.get("response_json")).get("result");
            Map<String,Object> shop = "order-create".equals(operation)
                    ? db.optional("select kind from hx_shop where id=?", number(body, "shopId"))
                    : null;
            if ("order-create".equals(operation)) require(shop != null, "商城不存在");
            if (shop != null && !"WHOLESALE".equals(str(shop, "kind"))) {
                verifiedCachedRetailOrder(actor, body, result);
            }
            return result;
        }
        Object result = action.get();
        db.jdbc().update("insert into hx_idempotency(actor_key,request_key,operation,request_hash,response_json) values(?,?,?,?,?)",
                actor.key(), key, operation, hash, db.json(map("result", result)));
        return result;
    }

    private void verifiedCachedRetailOrder(Actor actor, Map<String,Object> body, Object result) {
        // 旧包缓存须有与订单创建审计一致的协议快照；合法历史版本的重试仍返回原单。
        require(Boolean.TRUE.equals(body.get("orderConsent")), "请确认购买协议");
        String policyId = str(body, "agreementPolicyId");
        String version = str(body, "agreementVersion");
        require(!policyId.isEmpty() && !version.isEmpty(), "购买协议确认信息不完整");
        require(result instanceof Map, "历史订单响应无效");
        @SuppressWarnings("unchecked") Map<String,Object> orderResult = (Map<String,Object>)result;
        String orderId = str(orderResult, "id");
        Map<String,Object> order = db.optional(
                "select buyer_id,shop_id from hx_order where id=?", orderId);
        require(order != null && number(order, "buyer_id") == actor.id
                && number(order, "shop_id") == number(body, "shopId"), "历史订单响应无效");
        Map<String,Object> audit = db.optional(
                "select after_json from hx_audit where action='ORDER_CREATE' and reference_id=?"
                        + " and actor_id=? and shop_id=? order by id desc limit 1",
                orderId, actor.id, number(body, "shopId"));
        require(audit != null, "历史订单缺少购买协议确认记录");
        Map<String,Object> snapshot = db.parse(audit.get("after_json"));
        require(policyId.equals(str(snapshot, "agreementPolicyId"))
                && version.equals(str(snapshot, "agreementVersion")),
                "历史订单缺少购买协议确认记录");
    }
}
