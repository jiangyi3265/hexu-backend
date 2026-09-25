package com.ruoyi.hexu;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Service
public class HexuCommands {
    private final HexuStore db;
    public HexuCommands(HexuStore db){this.db=db;}
    @Transactional public Object execute(Actor a,String key,String operation,Map<String,Object> body,Supplier<Object> action){require(key!=null&&key.matches("[A-Za-z0-9_-]{8,100}"),"写操作须携带8至100位Idempotency-Key");db.one(a.staff?"select user_id from sys_user where user_id=? for update":"select id from hx_member where id=? for update",a.id);String hash=DigestUtils.md5DigestAsHex((operation+db.json(body)).getBytes(StandardCharsets.UTF_8));Map<String,Object>prior=db.optional("select * from hx_idempotency where actor_key=? and request_key=?",a.key(),key);if(prior!=null){require(operation.equals(str(prior,"operation"))&&hash.equals(str(prior,"request_hash")),"幂等键已用于不同请求");return db.parse(prior.get("response_json")).get("result");}Object result=action.get();db.jdbc().update("insert into hx_idempotency(actor_key,request_key,operation,request_hash,response_json) values(?,?,?,?,?)",a.key(),key,operation,hash,db.json(map("result",result)));return result;}
}
