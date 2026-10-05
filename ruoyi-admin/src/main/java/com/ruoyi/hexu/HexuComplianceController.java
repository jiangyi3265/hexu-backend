package com.ruoyi.hexu;

import static com.ruoyi.hexu.HexuStore.*;

import com.ruoyi.common.annotation.Anonymous;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@Anonymous
@RestController
@RequestMapping({"/hexu/admin", "/hexu/app/management"})
public class HexuComplianceController {
  private final HexuIdentity auth;
  private final HexuCommands commands;
  private final HexuMerchantOnboarding merchant;
  private final HexuPolicies policies;

  public HexuComplianceController(
      HexuIdentity auth,
      HexuCommands commands,
      HexuMerchantOnboarding merchant,
      HexuPolicies policies) {
    this.auth = auth;
    this.commands = commands;
    this.merchant = merchant;
    this.policies = policies;
  }

  private Actor actor(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/hexu/admin/") ? auth.admin() : auth.member(request);
  }

  @GetMapping("/merchant")
  public Object merchant(HttpServletRequest request, @RequestParam long shopId) {
    return HexuAppController.ok(merchant.status(actor(request), shopId));
  }

  @PostMapping("/merchant/{action}")
  public Object merchantAction(
      HttpServletRequest request,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    Actor a = actor(request);
    long shop = number(body, "shopId");
    return HexuAppController.ok(
        commands.execute(
            a,
            key,
            "merchant/" + action,
            body,
            () -> {
              switch (action) {
                case "submit":
                  return merchant.submit(a, shop, body);
                case "authorize":
                  return merchant.authorize(a, shop, body);
                case "certificate":
                  return merchant.certificate(a, shop, body);
                case "reference":
                  return merchant.reference(a, shop, body);
                case "verify":
                  return merchant.verify(a, shop, body);
                default:
                  throw new BusinessException("商户操作无效");
              }
            }));
  }

  @GetMapping("/policies")
  public Object policies(HttpServletRequest request, @RequestParam long shopId) {
    return HexuAppController.ok(policies.list(actor(request), shopId));
  }

  @PostMapping("/policies/publish")
  public Object publish(
      HttpServletRequest request,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    Actor a = actor(request);
    return HexuAppController.ok(
        commands.execute(
            a,
            key,
            "policies/publish",
            body,
            () -> policies.publish(a, number(body, "shopId"), body)));
  }

  @GetMapping("/policies/consents")
  public Object consents(HttpServletRequest request, @RequestParam long shopId) {
    return HexuAppController.ok(policies.audit(actor(request), shopId));
  }
}
