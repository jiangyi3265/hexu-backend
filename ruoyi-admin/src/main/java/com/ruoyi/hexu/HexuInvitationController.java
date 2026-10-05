package com.ruoyi.hexu;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;

@Anonymous @RestController @RequestMapping("/hexu/app")
public class HexuInvitationController {
    private final HexuIdentity auth;
    private final HexuCatalogService catalog;
    public HexuInvitationController(HexuIdentity auth,HexuCatalogService catalog){this.auth=auth;this.catalog=catalog;}
    @GetMapping("/invitation-context") public Object context(HttpServletRequest request,@RequestParam long shopId,@RequestParam String invite){return HexuAppController.ok(catalog.invitationContext(auth.member(request),shopId,invite));}
}
