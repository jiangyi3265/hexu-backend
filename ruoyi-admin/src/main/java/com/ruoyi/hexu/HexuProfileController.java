package com.ruoyi.hexu;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@Anonymous
@RestController
@RequestMapping("/hexu/app/profile")
public class HexuProfileController {
    private final HexuIdentity auth;
    private final HexuProfileService profiles;
    private final WechatIdentityGateway gateway;
    public HexuProfileController(HexuIdentity auth,HexuProfileService profiles,WechatIdentityGateway gateway){this.auth=auth;this.profiles=profiles;this.gateway=gateway;}
    @GetMapping public Object read(HttpServletRequest request){return HexuAppController.ok(profiles.read(auth.member(request)));}
    @PostMapping public Object save(HttpServletRequest request,@RequestBody Map<String,Object> body){return HexuAppController.ok(profiles.save(auth.member(request),body));}
    @PostMapping("/phone") public Object phone(HttpServletRequest request,@RequestBody Map<String,Object> body){
        com.ruoyi.hexu.BusinessException.require(body.size()==1&&body.get("code") instanceof String,"请使用微信手机号授权按钮");
        return HexuAppController.ok(profiles.authorizePhone(auth.member(request),(String)body.get("code"),gateway));
    }
}
