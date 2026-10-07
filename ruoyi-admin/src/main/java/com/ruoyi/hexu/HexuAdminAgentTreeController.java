package com.ruoyi.hexu;

import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static com.ruoyi.hexu.HexuAppController.ok;
import static com.ruoyi.hexu.HexuStore.*;

@RestController
@RequestMapping("/hexu/admin/agent-tree")
public class HexuAdminAgentTreeController {
    private final HexuIdentity auth;
    private final HexuAgentDirectory directory;
    private final HexuCommands commands;

    public HexuAdminAgentTreeController(HexuIdentity auth,HexuAgentDirectory directory,HexuCommands commands){
        this.auth=auth;this.directory=directory;this.commands=commands;
    }

    @GetMapping("/children")
    public Object children(@RequestParam long shopId,@RequestParam(defaultValue="0") long parentId,
                           @RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="50") int size){
        return ok(directory.children(auth.admin(),shopId,parentId,afterId,size));
    }

    @GetMapping("/search")
    public Object search(@RequestParam long shopId,@RequestParam(defaultValue="") String query,
                         @RequestParam(defaultValue="") String region,@RequestParam(defaultValue="0") long afterId,
                         @RequestParam(defaultValue="50") int size){
        return ok(directory.search(auth.admin(),shopId,query,region,afterId,size));
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable long id,@RequestParam long shopId){return ok(directory.detail(auth.admin(),shopId,id));}

    @GetMapping("/regions")
    public Object regions(@RequestParam long shopId){return ok(directory.regions(auth.admin(),shopId));}

    @GetMapping("/region-options")
    public Object regionOptions(@RequestParam long shopId,@RequestParam(defaultValue="") String query){
        return ok(directory.regionOptions(auth.admin(),shopId,query));
    }

    @PostMapping("/{id}/regions")
    public Object setRegions(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,
                             @RequestBody Map<String,Object> body){
        Actor actor=auth.admin();
        long shop=number(body,"shopId");
        BusinessException.require(body.get("regions") instanceof java.util.List,"经营区域格式无效");
        return ok(commands.execute(actor,key,"agent-tree/"+id+"/regions",body,
            ()->directory.setRegions(actor,shop,id,(java.util.List<?>)body.get("regions"),str(body,"reason"))));
    }
}
