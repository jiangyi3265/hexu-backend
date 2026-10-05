package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HexuFulfillment {
  private final HexuStore db;

  public HexuFulfillment(HexuStore db) {
    this.db = db;
  }

  public List<Map<String, Object>> pickList(Actor actor, long shop) {
    db.manage(actor, shop, "WAREHOUSE", "ORDER");
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> o :
        db.jdbc()
            .queryForList(
                "select o.id,o.shop_id,o.buyer_id,o.status,o.created_at,p.status pick_status from"
                    + " hx_order o left join hx_pick_task p on p.order_id=o.id where o.shop_id=?"
                    + " and o.status in ('PAID','SHIPPED') order by o.created_at desc limit 200",
                shop)) {
      List<Map<String, Object>> items = items(str(o, "id"));
      result.add(
          map(
              "orderId",
              o.get("id"),
              "shopId",
              o.get("shop_id"),
              "buyerId",
              o.get("buyer_id"),
              "orderStatus",
              o.get("status"),
              "pickStatus",
              o.get("pick_status") == null ? "NOT_STARTED" : o.get("pick_status"),
              "createdAt",
              o.get("created_at"),
              "itemCount",
              items.size(),
              "items",
              items));
    }
    return result;
  }

  private List<Map<String, Object>> items(String order) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> line :
        db.jdbc()
            .queryForList(
                "select sku_id,name,qty from hx_order_line where order_id=? order by id", order))
      rows.add(map("skuId", line.get("sku_id"), "name", line.get("name"), "qty", line.get("qty")));
    return rows;
  }

  @Transactional
  public Map<String, Object> pick(Actor actor, String order, String action) {
    Map<String, Object> o = db.one("select * from hx_order where id=? for update", order);
    long shop = number(o, "shop_id");
    db.manage(actor, shop, "WAREHOUSE", "ORDER");
    require("PAID".equals(str(o, "status")), "仅已支付未发货订单可拣货");
    Map<String, Object> task =
        db.optional("select * from hx_pick_task where order_id=? for update", order);
    if ("start".equals(action)) {
      require(task == null || "NOT_STARTED".equals(str(task, "status")), "拣货已经开始或完成");
      if (task == null)
        db.jdbc()
            .update(
                "insert into hx_pick_task(order_id,shop_id,status,started_by,started_at)"
                    + " values(?,?,'IN_PROGRESS',?,CURRENT_TIMESTAMP)",
                order,
                shop,
                actor.id);
      else
        db.jdbc()
            .update(
                "update hx_pick_task set"
                    + " status='IN_PROGRESS',started_by=?,started_at=CURRENT_TIMESTAMP where"
                    + " order_id=?",
                actor.id,
                order);
    } else if ("complete".equals(action)) {
      require(task != null && "IN_PROGRESS".equals(str(task, "status")), "请先开始拣货");
      db.jdbc()
          .update(
              "update hx_pick_task set"
                  + " status='COMPLETED',completed_by=?,completed_at=CURRENT_TIMESTAMP where"
                  + " order_id=?",
              actor.id,
              order);
    } else throw new BusinessException("拣货操作无效");
    db.audit(actor, shop, "PICK_" + action.toUpperCase(Locale.ROOT), order, null, null);
    return db.one("select * from hx_pick_task where order_id=?", order);
  }

  public Map<String, Object> print(Actor actor, String order) {
    Map<String, Object> o = db.one("select * from hx_order where id=?", order);
    long shop = number(o, "shop_id");
    db.manage(actor, shop, "WAREHOUSE", "ORDER");
    require(
        Arrays.asList("PAID", "SHIPPED", "COMPLETED").contains(str(o, "status")), "仅已支付订单可打印拣货单");
    Map<String, Object> address = db.parse(o.get("address_json")),
        s = db.one("select name from hx_shop where id=?", shop),
        task = db.optional("select status from hx_pick_task where order_id=?", order);
    Map<String, Object> shipping =
        o.get("shipping_json") == null ? Collections.emptyMap() : db.parse(o.get("shipping_json"));
    return map(
        "orderId",
        order,
        "shopId",
        shop,
        "shopName",
        s.get("name"),
        "recipient",
        address.get("name"),
        "address",
        address,
        "carrier",
        shipping.get("carrier"),
        "tracking",
        shipping.get("tracking"),
        "items",
        items(order),
        "pickStatus",
        task == null ? "NOT_STARTED" : task.get("status"),
        "printedAt",
        java.time.Instant.now().toString());
  }

  public List<Map<String, Object>> aftersaleLinks(Actor actor, long shop) {
    db.manage(actor, shop, "SUPPORT", "OWNER", "WAREHOUSE");
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> r :
        db.jdbc()
            .queryForList(
                "select l.*,cr.order_id customer_order_id,cr.status"
                    + " customer_refund_status,wo.shop_id wholesale_shop_id,wo.buyer_id wholesale_buyer_id,wr.status"
                    + " wholesale_refund_status from hx_direct_sale_aftersale l join hx_refund cr"
                    + " on cr.id=l.customer_refund_id join hx_order wo on"
                    + " wo.id=l.wholesale_order_id left join hx_refund wr on"
                    + " wr.id=l.wholesale_refund_id where l.shop_id=? order by l.created_at desc",
                shop)) result.add(linkRow(r));
    return result;
  }

  private Map<String, Object> linkRow(Map<String, Object> row) {
    return map(
        "id",
        row.get("id"),
        "shopId",
        row.get("shop_id"),
        "customerRefundId",
        row.get("customer_refund_id"),
        "customerOrderId",
        row.get("customer_order_id"),
        "customerRefundStatus",
        row.get("customer_refund_status"),
        "wholesaleOrderId",
        row.get("wholesale_order_id"),
        "wholesaleShopId",
        row.get("wholesale_shop_id"),
        "wholesaleBuyerId",
        row.get("wholesale_buyer_id"),
        "wholesaleRefundId",
        row.get("wholesale_refund_id"),
        "wholesaleRefundStatus",
        row.get("wholesale_refund_status"),
        "skuId",
        row.get("sku_id"),
        "qty",
        row.get("qty"),
        "createdAt",
        row.get("created_at"));
  }

  public List<Map<String, Object>> aftersaleCandidates(
      Actor actor, long shop, String customerRefundId) {
    db.manage(actor, shop, "SUPPORT", "OWNER", "WAREHOUSE");
    Map<String, Object> customer =
        db.one(
            "select r.*,l.sku_id,o.address_json from hx_refund r join hx_order_line l on"
                + " l.id=r.line_id join hx_order o on o.id=r.order_id where r.id=? and r.shop_id=?"
                + " and o.order_type='DEALER_RETAIL'",
            customerRefundId,
            shop);
    require(
        !Arrays.asList("PENDING", "REJECTED", "CLOSED").contains(str(customer, "status")),
        "请先由经销商受理客户售后");
    Map<String, Object> address = db.parse(customer.get("address_json"));
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> candidate :
        db.jdbc()
            .queryForList(
                "select o.id,o.shop_id,o.status,o.address_json,l.qty from hx_order o join hx_shop s"
                    + " on s.id=o.shop_id join hx_order_line l on l.order_id=o.id where"
                    + " o.order_type='DIRECT_SHIP' and s.kind='WHOLESALE' and"
                    + " o.destination_shop_id=? and o.status in ('PAID','SHIPPED','COMPLETED') and"
                    + " l.sku_id=? order by o.created_at desc limit 200",
                shop,
                customer.get("sku_id"))) {
      Map<String, Object> sourceAddress = db.parse(candidate.get("address_json"));
      if (!sameRecipient(address, sourceAddress)) continue;
      long linked =
          db.jdbc()
              .queryForObject(
                  "select coalesce(sum(qty),0) from hx_direct_sale_aftersale where"
                      + " wholesale_order_id=? and sku_id=? and customer_refund_id<>?",
                  Long.class,
                  candidate.get("id"),
                  customer.get("sku_id"),
                  customerRefundId);
      long available = number(candidate, "qty") - linked;
      if (available < number(customer, "qty")) continue;
      result.add(
          map(
              "wholesaleOrderId",
              candidate.get("id"),
              "wholesaleShopId",
              candidate.get("shop_id"),
              "orderStatus",
              candidate.get("status"),
              "skuId",
              customer.get("sku_id"),
              "availableQty",
              available));
    }
    return result;
  }

  private boolean sameRecipient(Map<String, Object> left, Map<String, Object> right) {
    for (String field :
        Arrays.asList("name", "phone", "region", "province", "city", "district", "detail"))
      if (!str(left, field).equals(str(right, field))) return false;
    return true;
  }

  @Transactional
  public Map<String, Object> linkAftersale(Actor actor, long shop, Map<String, Object> body) {
    db.manage(actor, shop, "SUPPORT", "OWNER");
    String customerId = str(body, "customerRefundId"),
        wholesaleId = str(body, "wholesaleOrderId"),
        wholesaleRefundId = str(body, "wholesaleRefundId");
    Map<String, Object> customer =
        db.one(
            "select r.*,l.sku_id,o.address_json from hx_refund r join hx_order_line l on"
                + " l.id=r.line_id join hx_order o on o.id=r.order_id where r.id=? and r.shop_id=?"
                + " and o.order_type='DEALER_RETAIL' for update",
            customerId,
            shop);
    require(
        !Arrays.asList("PENDING", "REJECTED", "CLOSED").contains(str(customer, "status")),
        "请先由经销商受理客户售后");
    Map<String, Object> source =
        db.one(
            "select o.* from hx_order o join hx_shop s on s.id=o.shop_id where o.id=? and"
                + " o.order_type='DIRECT_SHIP' and s.kind='WHOLESALE' and o.destination_shop_id=?"
                + " for update",
            wholesaleId,
            shop);
    require(
        Arrays.asList("PAID", "SHIPPED", "COMPLETED").contains(str(source, "status")),
        "直发采购单未支付或已关闭");
    Map<String, Object> wholesaleLine =
        db.one(
            "select * from hx_order_line where order_id=? and sku_id=?",
            wholesaleId,
            str(customer, "sku_id"));
    require(number(customer, "qty") <= number(wholesaleLine, "qty"), "直发采购商品数量不足");
    Map<String, Object> sourceAddress = db.parse(source.get("address_json")),
        customerAddress = db.parse(customer.get("address_json"));
    require(sameRecipient(sourceAddress, customerAddress), "直发采购的实际收货人和地址与客户订单不一致");
    if (!wholesaleRefundId.isEmpty()) {
      Map<String, Object> wholesaleRefund =
          db.one(
              "select * from hx_refund where id=? and order_id=? and line_id=?",
              wholesaleRefundId,
              wholesaleId,
              wholesaleLine.get("id"));
      require(number(wholesaleRefund, "qty") >= number(customer, "qty"), "公司批发售后数量小于客户售后数量");
    }
    Map<String, Object> existing =
        db.optional(
            "select * from hx_direct_sale_aftersale where customer_refund_id=? for update",
            customerId);
    long linked =
        db.jdbc()
            .queryForObject(
                "select coalesce(sum(qty),0) from hx_direct_sale_aftersale where"
                    + " wholesale_order_id=? and sku_id=? and customer_refund_id<>?",
                Long.class,
                wholesaleId,
                customer.get("sku_id"),
                customerId);
    require(linked + number(customer, "qty") <= number(wholesaleLine, "qty"), "该批发商品的售后关联数量已满");
    if (!wholesaleRefundId.isEmpty()) {
      long refundLinked =
          db.jdbc()
              .queryForObject(
                  "select coalesce(sum(qty),0) from hx_direct_sale_aftersale where"
                      + " wholesale_refund_id=? and customer_refund_id<>?",
                  Long.class,
                  wholesaleRefundId,
                  customerId);
      Map<String, Object> wholesaleRefund =
          db.one("select qty from hx_refund where id=?", wholesaleRefundId);
      require(
          refundLinked + number(customer, "qty") <= number(wholesaleRefund, "qty"), "公司批发售后关联数量已满");
    }
    String id;
    if (existing == null) {
      id = id("DSL");
      db.jdbc()
          .update(
              "insert into"
                  + " hx_direct_sale_aftersale(id,shop_id,customer_refund_id,wholesale_order_id,wholesale_refund_id,sku_id,qty)"
                  + " values(?,?,?,?,?,?,?)",
              id,
              shop,
              customerId,
              wholesaleId,
              wholesaleRefundId.isEmpty() ? null : wholesaleRefundId,
              customer.get("sku_id"),
              customer.get("qty"));
    } else {
      require(
          shop == number(existing, "shop_id")
              && wholesaleId.equals(str(existing, "wholesale_order_id")),
          "该客户售后已关联其他批发订单");
      require(
          str(existing, "wholesale_refund_id").isEmpty()
              || str(existing, "wholesale_refund_id").equals(wholesaleRefundId),
          "已关联公司售后不可替换");
      id = str(existing, "id");
      if (!wholesaleRefundId.isEmpty())
        db.jdbc()
            .update(
                "update hx_direct_sale_aftersale set wholesale_refund_id=? where id=?",
                wholesaleRefundId,
                id);
    }
    db.audit(
        actor,
        shop,
        "DIRECT_SALE_AFTERSALE_LINK",
        id,
        null,
        map(
            "customerRefundId",
            customerId,
            "wholesaleOrderId",
            wholesaleId,
            "wholesaleRefundId",
            wholesaleRefundId));
    return aftersaleLinks(actor, shop).stream()
        .filter(r -> id.equals(str(r, "id")))
        .findFirst()
        .orElseThrow(() -> new BusinessException("售后关联保存失败"));
  }
}
