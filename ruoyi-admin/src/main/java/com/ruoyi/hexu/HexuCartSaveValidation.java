package com.ruoyi.hexu;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.bool;
import static com.ruoyi.hexu.HexuStore.number;
import static com.ruoyi.hexu.HexuStore.str;

/** 购物车保存校验；不改变历史失效行的读取和删除方式。 */
final class HexuCartSaveValidation {
    private static final int MAX_LINES = 100;
    private static final long MAX_QUANTITY = 100000;
    private final HexuStore db;

    HexuCartSaveValidation(HexuStore db) {
        this.db = db;
    }

    void validate(Actor actor, long shop, Map<String,Object> body, Map<String,Object> previous) {
        require(body.get("items") instanceof List, "购物车商品格式无效");
        List<?> items = (List<?>)body.get("items");
        require(items.size() <= MAX_LINES, "购物车商品超过上限");
        Map<String,Long> previousQuantities = previousQuantities(previous);
        Set<String> seen = new HashSet<>();
        boolean accessChecked = false;
        for (Object value : items) {
            require(value instanceof Map, "购物车商品格式无效");
            Map<?,?> raw = (Map<?,?>)value;
            require(raw.get("id") instanceof String, "购物车商品编号无效");
            String id = (String)raw.get("id");
            require(id.matches("[A-Za-z0-9_-]{1,64}") && seen.add(id), "购物车商品编号无效或重复");
            require(raw.get("qty") instanceof Number, "购物车数量必须为整数");
            @SuppressWarnings("unchecked") Map<String,Object> line = (Map<String,Object>)raw;
            long quantity = number(line, "qty");
            require(quantity > 0 && quantity <= MAX_QUANTITY, "购物车数量必须为1至100000的整数");

            Map<String,Object> sku = db.optional("select status,available from hx_sku where shop_id=? and id=?", shop, id);
            boolean unchanged = previousQuantities.getOrDefault(id, -1L) == quantity;
            if (sku == null || !"ACTIVE".equals(str(sku, "status"))) {
                // 旧购物车的失效行可继续展示、取消勾选或删除，不允许伪造新失效行或加量。
                require(unchanged, "商品已失效，请从购物车移除");
            } else {
                if (!unchanged && !accessChecked) {
                    require(bool(new HexuCatalogService(db).access(actor, shop), "allowed"), "请返回所属商城");
                    accessChecked = true;
                }
                // 库存下降后可从商品列表移除该行；只要仍提交该行，就必须符合当前库存。
                require(quantity <= number(sku, "available"), "商品库存不足，请调整购物车数量");
            }
        }
    }

    private Map<String,Long> previousQuantities(Map<String,Object> previous) {
        Map<String,Long> quantities = new HashMap<>();
        if (previous == null) return quantities;
        Object items = db.parse(previous.get("body_json")).get("items");
        if (!(items instanceof List)) return quantities;
        for (Object value : (List<?>)items) {
            if (!(value instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String,Object> line = (Map<String,Object>)value;
            if (line.get("id") instanceof String && line.get("qty") instanceof Number) {
                try {
                    quantities.put((String)line.get("id"), number(line, "qty"));
                } catch (BusinessException ignored) {
                    // 旧脏数据仍能通过删除清理，不能作为可继续保留的有效数量。
                }
            }
        }
        return quantities;
    }
}
