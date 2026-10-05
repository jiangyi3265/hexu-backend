package com.ruoyi.hexu;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
/** Explicit local integration sandbox. This controller does not exist in production profiles. */
@Profile("hexu-dev") @ConditionalOnProperty(name="hexu.sandbox.enabled",havingValue="true")
@Transactional @Anonymous @RestController @RequestMapping("/hexu/dev")
public class HexuDevController {
    private final HexuIdentity auth;private final HexuStore db;private final HexuOrderService orders;private final HexuFinanceService finance;
    private static final Set<String> TRUSTED_BROWSER_ORIGINS=Set.of("http://127.0.0.1:4177","http://localhost:4177","http://127.0.0.1","http://localhost");
    @Value("${hexu.dev-key:}") private String key;
    public HexuDevController(HexuIdentity auth,HexuStore db,HexuOrderService orders,HexuFinanceService finance){this.auth=auth;this.db=db;this.orders=orders;this.finance=finance;}
    void check(HttpServletRequest r){
        require(Arrays.asList("127.0.0.1","0:0:0:0:0:0:0:1","::1").contains(r.getRemoteAddr()),"测试接口仅限本机");
        String origin=r.getHeader("Origin"),fetchSite=r.getHeader("Sec-Fetch-Site");
        // 浏览器即使通过本机代理发请求，也不能以外部网页的 Origin 使用开发入口。
        require(origin==null||TRUSTED_BROWSER_ORIGINS.contains(origin),"测试来源不可信");
        require(!"cross-site".equalsIgnoreCase(fetchSite),"测试来源不可信");
        String supplied=r.getHeader("X-Hexu-Dev-Key");
        if(supplied==null||supplied.isBlank()){
            // 原生开发者工具没有密钥，只允许无浏览器来源的本机沙盒请求；浏览器必须走密钥代理。
            require(origin==null&&"1".equals(r.getHeader("X-Hexu-Mp-Dev")),"测试密钥无效");
            return;
        }
        require(key.length()>=24&&java.security.MessageDigest.isEqual(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"测试密钥无效");
    }
    @PostMapping("/login") public Object login(HttpServletRequest r,@RequestBody Map<String,Object>b){check(r);return HexuAppController.ok(map("token",auth.session(number(b,"memberId")),"sandbox",true));}
    @PostMapping("/payment/{id}") public Object payment(HttpServletRequest r,@PathVariable String id){check(r);Map<String,Object>p=db.one("select p.*,o.shop_id from hx_payment p join hx_order o on o.id=p.order_id where p.id=?",id);boolean fresh=!"SUCCESS".equals(str(p,"status"));Map<String,Object>out=orders.paymentConfirmed(id,str(p,"merchant_no"),number(p,"amount"),"SANDBOX-"+id);if(fresh)db.audit(new Actor(0,true,true),number(p,"shop_id"),"SANDBOX_PAYMENT",id,null,map("amount",p.get("amount"),"sandbox",true));Map<String,Object>result=new LinkedHashMap<>(out);result.put("sandbox",true);return HexuAppController.ok(result);}
    @PostMapping("/refund/{id}") public Object refund(HttpServletRequest r,@PathVariable String id){check(r);Map<String,Object>before=db.one("select status,shop_id,amount from hx_refund where id=?",id);Map<String,Object>out=finance.refundConfirmed(id);if(!"SUCCESS".equals(str(before,"status")))db.audit(new Actor(0,true,true),number(before,"shop_id"),"SANDBOX_REFUND",id,before,map("status",out.get("status"),"sandbox",true));Map<String,Object>result=new LinkedHashMap<>(out);result.put("sandbox",true);return HexuAppController.ok(result);}
    @PostMapping("/payout/{id}") public Object payout(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object>b){check(r);Map<String,Object>before=db.one("select status,shop_id,net from hx_withdrawal where id=?",id);Map<String,Object>out=finance.payoutResult(id,bool(b,"success"),"SANDBOX-"+id,str(b,"reason"));if(!Arrays.asList("PAID","FAILED").contains(str(before,"status")))db.audit(new Actor(0,true,true),number(before,"shop_id"),"SANDBOX_PAYOUT",id,before,map("status",out.get("status"),"sandbox",true));Map<String,Object>result=new LinkedHashMap<>(out);result.put("sandbox",true);return HexuAppController.ok(result);}
}
