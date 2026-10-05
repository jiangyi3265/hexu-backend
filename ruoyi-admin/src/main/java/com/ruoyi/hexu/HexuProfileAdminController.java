package com.ruoyi.hexu;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/hexu/admin/members")
public class HexuProfileAdminController {
    private final HexuIdentity auth;
    private final HexuProfileService profiles;
    public HexuProfileAdminController(HexuIdentity auth,HexuProfileService profiles){this.auth=auth;this.profiles=profiles;}
    @GetMapping("/{id}/profile") public Object read(@PathVariable long id,@RequestParam long shopId){return HexuAppController.ok(profiles.managementRead(auth.admin(),shopId,id));}
}
