package com.ruoyi.hexu;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
/** Unverified requests never reach the durable business inbox. */
@Anonymous @RestController @RequestMapping("/hexu/channel")
public class HexuChannelEvents {
 private final PaymentGateway gateway;private final HexuChannelInbox inbox;
 public HexuChannelEvents(PaymentGateway gateway,HexuChannelInbox inbox){this.gateway=gateway;this.inbox=inbox;}
 @PostMapping("/notify") public Object notify(@RequestBody String raw,@RequestHeader Map<String,String> headers){String key=inbox.accept(gateway.verifyNotification(raw,headers));try{return HexuAppController.ok(inbox.apply(key));}catch(RuntimeException e){inbox.failed(key,e.getMessage());throw e;}}
}
