package com.ruoyi.hexu;
import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.Map;
/** Unverified requests never reach the durable business inbox. */
@Anonymous @RestController @RequestMapping("/hexu/channel")
public class HexuChannelEvents {
 private final PaymentGateway gateway;private final HexuChannelInbox inbox;
 public HexuChannelEvents(PaymentGateway gateway,HexuChannelInbox inbox){this.gateway=gateway;this.inbox=inbox;}
 @PostMapping("/notify") public ResponseEntity<Map<String,Object>> notify(@RequestBody String raw,@RequestHeader Map<String,String> headers){
  String key;
  try{key=inbox.accept(gateway.verifyNotification(raw,headers));}catch(RuntimeException e){return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(HexuStore.map("code","FAIL","message","验签或回调内容无效"));}
  try{inbox.apply(key);return ResponseEntity.ok(HexuStore.map("code","SUCCESS","message","成功"));}
  catch(RuntimeException e){inbox.failed(key,e.getMessage());return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(HexuStore.map("code","FAIL","message","处理失败，请重试"));}
 }
}
