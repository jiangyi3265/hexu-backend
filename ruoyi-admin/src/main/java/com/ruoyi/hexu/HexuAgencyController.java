package com.ruoyi.hexu;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.ruoyi.hexu.HexuStore.*;
@RestController @RequestMapping("/hexu/admin/agency")
public class HexuAgencyController {
 private final HexuIdentity auth;private final HexuAgencyService agency;private final HexuCommands commands;
 public HexuAgencyController(HexuIdentity auth,HexuAgencyService agency,HexuCommands commands){this.auth=auth;this.agency=agency;this.commands=commands;}
 @PostMapping("/team-migration") public Object propose(@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object>b){Actor a=auth.admin();return HexuAppController.ok(commands.execute(a,key,"team-propose",b,()->agency.propose(a,number(b,"shopId"),b)));}
 @PostMapping("/team-migration/{id}/review") public Object review(@PathVariable String id,@RequestHeader("Idempotency-Key")String key,@RequestBody Map<String,Object>b){Actor a=auth.admin();return HexuAppController.ok(commands.execute(a,key,"team-review/"+id,b,()->agency.review(a,id,b)));}
}
