package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

import com.ruoyi.common.annotation.Anonymous;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@Anonymous
@RestController
@RequestMapping({"/hexu/admin", "/hexu/app/management"})
public class HexuFulfillmentController {
  private final HexuIdentity auth;
  private final HexuStore db;
  private final HexuCommands commands;
  private final HexuFulfillment fulfillment;
  private final TrackingGateway tracking;

  public HexuFulfillmentController(
      HexuIdentity auth,
      HexuStore db,
      HexuCommands commands,
      HexuFulfillment fulfillment,
      TrackingGateway tracking) {
    this.auth = auth;
    this.db = db;
    this.commands = commands;
    this.fulfillment = fulfillment;
    this.tracking = tracking;
  }

  private Actor actor(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/hexu/admin/") ? auth.admin() : auth.member(request);
  }

  @GetMapping("/fulfillment/pick")
  public Object picks(HttpServletRequest request, @RequestParam long shopId) {
    return HexuAppController.ok(fulfillment.pickList(actor(request), shopId));
  }

  @GetMapping("/fulfillment/pick/{id}/print")
  public Object print(HttpServletRequest request, @PathVariable String id) {
    return HexuAppController.ok(fulfillment.print(actor(request), id));
  }

  @PostMapping("/fulfillment/pick/{id}/{action}")
  public Object pick(
      HttpServletRequest request,
      @PathVariable String id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody(required = false) Map<String, Object> body) {
    Actor a = actor(request);
    Map<String, Object> requestBody = body == null ? Collections.emptyMap() : body;
    return HexuAppController.ok(
        commands.execute(
            a,
            key,
            "pick/" + id + "/" + action,
            requestBody,
            () -> fulfillment.pick(a, id, action)));
  }

  @GetMapping("/aftersale-links")
  public Object links(HttpServletRequest request, @RequestParam long shopId) {
    return HexuAppController.ok(fulfillment.aftersaleLinks(actor(request), shopId));
  }

  @GetMapping("/aftersale-links/candidates")
  public Object candidates(
      HttpServletRequest request,
      @RequestParam long shopId,
      @RequestParam String customerRefundId) {
    return HexuAppController.ok(
        fulfillment.aftersaleCandidates(actor(request), shopId, customerRefundId));
  }

  @PostMapping("/aftersale-links")
  public Object link(
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    Actor a = actor(request);
    return HexuAppController.ok(
        commands.execute(
            a,
            key,
            "aftersale-link",
            body,
            () -> fulfillment.linkAftersale(a, number(body, "shopId"), body)));
  }

  @GetMapping("/orders/{id}/tracking")
  public Object managedTracking(HttpServletRequest request, @PathVariable String id) {
    Map<String, Object> order = db.one("select shop_id,shipping_json from hx_order where id=?", id);
    db.manage(actor(request), number(order, "shop_id"), "ORDER", "WAREHOUSE", "SUPPORT");
    return HexuAppController.ok(tracking(order));
  }

  @GetMapping("/tracking/configuration")
  public Object configuration(HttpServletRequest request, @RequestParam long shopId) {
    db.manage(actor(request), shopId, "WAREHOUSE", "ORDER", "SUPPORT");
    return HexuAppController.ok(map("configured", tracking.configured()));
  }

  private Map<String, Object> tracking(Map<String, Object> order) {
    if (order.get("shipping_json") == null)
      return map(
          "carrier",
          "",
          "tracking",
          "",
          "status",
          "UNAVAILABLE",
          "events",
          Collections.emptyList(),
          "contact",
          "");
    Map<String, Object> shipping = db.parse(order.get("shipping_json"));
    String carrier = str(shipping, "carrier"), waybill = str(shipping, "tracking");
    Map<String, Object> result = new LinkedHashMap<>(tracking.query(carrier, waybill));
    result.put("carrier", carrier);
    result.put("tracking", waybill);
    require(
        Arrays.asList("UNAVAILABLE", "IN_TRANSIT", "DELIVERED", "EXCEPTION")
            .contains(str(result, "status")),
        "物流接口返回无效状态");
    return result;
  }

  @Anonymous
  @RestController
  @RequestMapping("/hexu/app")
  public static class CustomerTracking {
    private final HexuIdentity auth;
    private final HexuStore db;
    private final TrackingGateway gateway;

    public CustomerTracking(HexuIdentity auth, HexuStore db, TrackingGateway gateway) {
      this.auth = auth;
      this.db = db;
      this.gateway = gateway;
    }

    @GetMapping("/orders/{id}/tracking")
    public Object get(HttpServletRequest request, @PathVariable String id) {
      Actor a = auth.member(request);
      Map<String, Object> order =
          db.one("select buyer_id,shipping_json from hx_order where id=?", id);
      require(number(order, "buyer_id") == a.id, "无权查看物流");
      if (order.get("shipping_json") == null)
        return HexuAppController.ok(
            map(
                "carrier",
                "",
                "tracking",
                "",
                "status",
                "UNAVAILABLE",
                "events",
                Collections.emptyList(),
                "contact",
                ""));
      Map<String, Object> shipping = db.parse(order.get("shipping_json"));
      String carrier = str(shipping, "carrier"), waybill = str(shipping, "tracking");
      Map<String, Object> result = new LinkedHashMap<>(gateway.query(carrier, waybill));
      result.put("carrier", carrier);
      result.put("tracking", waybill);
      return HexuAppController.ok(result);
    }
  }
}
