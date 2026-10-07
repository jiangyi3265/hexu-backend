package com.ruoyi.hexu;

import com.ruoyi.common.annotation.Anonymous;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import static com.ruoyi.hexu.HexuAppController.ok;

@Anonymous
@RestController
@RequestMapping("/hexu/app/agent-team")
public class HexuAppAgentTeamController {
    private final HexuIdentity auth;
    private final HexuAgentDirectory directory;

    public HexuAppAgentTeamController(HexuIdentity auth,HexuAgentDirectory directory){
        this.auth=auth;this.directory=directory;
    }

    @GetMapping("/children")
    public Object children(HttpServletRequest request,@RequestParam long shopId,@RequestParam long parentId,
                           @RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="50") int size){
        return ok(directory.ownChildren(auth.member(request),shopId,parentId,afterId,size));
    }

    @GetMapping("/{id}")
    public Object detail(HttpServletRequest request,@PathVariable long id,@RequestParam long shopId){
        return ok(directory.ownDetail(auth.member(request),shopId,id));
    }
}
