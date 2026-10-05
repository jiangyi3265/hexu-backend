package com.ruoyi.hexu;

import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;

/** 仅为当前会员的旧购物车补齐展示快照，不修改商品可售状态。 */
final class HexuCartReadModel {
    private static final int SNAPSHOT_BATCH_SIZE = 100;
    private final HexuStore db;
    private final Map<String,Object> publicAssets;

    HexuCartReadModel(HexuStore db, Map<String,Object> publicAssets) {
        this.db = db;
        this.publicAssets = publicAssets;
    }

    @SuppressWarnings("unchecked")
    List<Map<String,Object>> withLegacySnapshots(long shopId, List<Map<String,Object>> carts) {
        Set<String> missingIds = new LinkedHashSet<>();
        for (Map<String,Object> cart : carts) {
            for (Object value : cartItems(cart)) {
                if (!(value instanceof Map)) continue;
                Map<String,Object> line = (Map<String,Object>)value;
                String id = str(line, "id");
                if (id.matches("[A-Za-z0-9_-]{1,64}") && needsSnapshot(line)) missingIds.add(id);
            }
        }
        if (missingIds.isEmpty()) return carts;

        // 商品查询固定当前商城；旧购物车行不能借同名 SKU 读取其他商城资料。
        Map<String,Map<String,Object>> products = new HashMap<>();
        List<String> ids = new ArrayList<>(missingIds);
        for (int start = 0; start < ids.size(); start += SNAPSHOT_BATCH_SIZE) {
            List<String> batch = ids.subList(start, Math.min(start + SNAPSHOT_BATCH_SIZE, ids.size()));
            List<Object> args = new ArrayList<>();
            args.add(shopId);
            args.addAll(batch);
            String marks = String.join(",", Collections.nCopies(batch.size(), "?"));
            for (Map<String,Object> product : db.jdbc().queryForList(
                    "select id,name,spec,asset from hx_sku where shop_id=? and id in (" + marks + ")", args.toArray())) {
                products.put(str(product, "id"), product);
            }
        }
        for (Map<String,Object> cart : carts) {
            for (Object value : cartItems(cart)) {
                if (!(value instanceof Map)) continue;
                Map<String,Object> line = (Map<String,Object>)value;
                Map<String,Object> product = products.get(str(line, "id"));
                if (product == null || !needsSnapshot(line)) continue;
                Map<String,Object> saved = line.get("snapshot") instanceof Map
                        ? new LinkedHashMap<>((Map<String,Object>)line.get("snapshot")) : new LinkedHashMap<>();
                if (!(saved.get("name") instanceof String) || ((String)saved.get("name")).trim().isEmpty()) saved.put("name", str(product, "name"));
                if (!(saved.get("spec") instanceof String)) saved.put("spec", str(product, "spec"));
                // 旧记录没有可信的上传图授权证明，只回显内置公开图片。
                if (!(saved.get("asset") instanceof String)) saved.put("asset", publicAsset(product));
                line.put("snapshot", saved);
            }
        }
        return carts;
    }

    private static List<?> cartItems(Map<String,Object> cart) {
        Object body = cart.get("body");
        Object items = body instanceof Map ? ((Map<?,?>)body).get("items") : null;
        return items instanceof List ? (List<?>)items : Collections.emptyList();
    }

    private static boolean needsSnapshot(Map<String,Object> line) {
        if (!(line.get("snapshot") instanceof Map)) return true;
        Map<?,?> saved = (Map<?,?>)line.get("snapshot");
        return !(saved.get("name") instanceof String) || ((String)saved.get("name")).trim().isEmpty()
                || !(saved.get("spec") instanceof String) || !(saved.get("asset") instanceof String);
    }

    private String publicAsset(Map<String,Object> product) {
        String asset = str(product, "asset");
        return publicAssets.containsKey(asset) || publicAssets.containsValue(asset) ? asset : "";
    }
}
