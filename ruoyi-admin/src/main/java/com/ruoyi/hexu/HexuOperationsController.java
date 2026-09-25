package com.ruoyi.hexu;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Anonymous @RestController @RequestMapping({"/hexu/app/management/operations","/hexu/admin/operations"})
public class HexuOperationsController {
 private final HexuIdentity auth;private final HexuStore db;private final HexuWorkflowService workflow;
 public HexuOperationsController(HexuIdentity a,HexuStore d,HexuWorkflowService w){auth=a;db=d;workflow=w;}
 private Actor actor(HttpServletRequest r){return r.getRequestURI().contains("/hexu/admin/")?auth.admin():auth.member(r);}
 @GetMapping("/staff") public Object staff(HttpServletRequest r,@RequestParam long shopId){return HexuAppController.ok(new HexuOperations(db).staff(actor(r),shopId));}
 @GetMapping("/staff-lookup") public Object lookup(HttpServletRequest r,@RequestParam long shopId,@RequestParam String login){return HexuAppController.ok(new HexuOperations(db).lookup(actor(r),shopId,login));}
 @GetMapping("/audit/{id}") public Object audit(HttpServletRequest r,@RequestParam long shopId,@PathVariable long id){return HexuAppController.ok(new HexuOperations(db).auditDetail(actor(r),shopId,id));}
 @GetMapping("/rules") public Object rules(HttpServletRequest r,@RequestParam long shopId){Actor a=actor(r);db.manage(a,shopId,"FINANCE");return HexuAppController.ok(map("canPublish",a.platform,"rules",db.jdbc().queryForList("select * from hx_rule order by effective_at desc,id desc limit 100")));}
 @GetMapping("/marketing-buyer") public Object buyer(HttpServletRequest r,@RequestParam long shopId,@RequestParam long customerId){Actor a=actor(r);db.manage(a,shopId,"FINANCE","OPERATOR");require(db.optional("select member_id from hx_binding where shop_id=? and member_id=?",shopId,customerId)!=null||db.agent(customerId,shopId)!=null,"客户不属于当前商城");Actor customer=new Actor(customerId,false,false);return HexuAppController.ok(map("coupons",workflow.coupons(customer,shopId),"points",db.pointSnapshot(0,customerId),"shopPoints",db.pointSnapshot(shopId,customerId)));}
}
