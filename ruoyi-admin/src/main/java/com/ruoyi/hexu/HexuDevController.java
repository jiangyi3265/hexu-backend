package com.ruoyi.hexu;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
/** Explicit local integration sandbox. This controller does not exist in production profiles. */
@Profile("hexu-dev") @Anonymous @RestController @RequestMapping("/hexu/dev")
public class HexuDevController {
    private final HexuIdentity auth;private final HexuStore db;private final HexuOrderService orders;private final HexuFinanceService finance;
    @Value("${hexu.dev-key:}") private String key;
    public HexuDevController(HexuIdentity auth,HexuStore db,HexuOrderService orders,HexuFinanceService finance){this.auth=auth;this.db=db;this.orders=orders;this.finance=finance;}
    private void check(HttpServletRequest r){require(Arrays.asList("127.0.0.1","0:0:0:0:0:0:0:1","::1").contains(r.getRemoteAddr()),"测试接口仅限本机");require(key.length()>=24&&java.security.MessageDigest.isEqual(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),String.valueOf(r.getHeader("X-Hexu-Dev-Key")).getBytes(java.nio.charset.StandardCharsets.UTF_8)),"测试密钥无效");}
    @PostMapping("/login") public Object login(HttpServletRequest r,@RequestBody Map<String,Object>b){check(r);return HexuAppController.ok(map("token",auth.session(number(b,"memberId")),"sandbox",true));}
    @PostMapping("/payment/{id}") public Object payment(HttpServletRequest r,@PathVariable String id){check(r);Map<String,Object>p=db.one("select * from hx_payment where id=?",id);return HexuAppController.ok(orders.paymentConfirmed(id,str(p,"merchant_no"),number(p,"amount"),"SANDBOX-"+id));}
    @PostMapping("/refund/{id}") public Object refund(HttpServletRequest r,@PathVariable String id){check(r);return HexuAppController.ok(finance.refundConfirmed(id));}
    @PostMapping("/payout/{id}") public Object payout(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object>b){check(r);return HexuAppController.ok(finance.payoutResult(id,bool(b,"success"),"SANDBOX-"+id,str(b,"reason")));}
}
