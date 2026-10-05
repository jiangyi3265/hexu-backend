package com.ruoyi.hexu;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Component
public class HexuIdentity {
    private final HexuStore db;
    public HexuIdentity(HexuStore db){this.db=db;}
    public Actor admin(){Long id=SecurityUtils.getUserId();return new Actor(id,SecurityUtils.isAdmin(id),true);}
    public Actor member(HttpServletRequest request){String auth=request.getHeader("Authorization");if(auth==null||!auth.startsWith("Bearer hx_"))throw new HexuMemberAuthException("请先登录商城账户");Map<String,Object>s=db.optional("select member_id from hx_session where token_hash=? and expires_at>CURRENT_TIMESTAMP",hash(auth.substring(7)));if(s==null)throw new HexuMemberAuthException("登录已失效，请重新登录");Actor a=new Actor(number(s,"member_id"),false,false);db.member(a);return a;}
    public String session(long member){db.one("select id from hx_member where id=? and status='ACTIVE'",member);byte[] random=new byte[32];new SecureRandom().nextBytes(random);String token="hx_"+Base64.getUrlEncoder().withoutPadding().encodeToString(random);db.jdbc().update("insert into hx_session(token_hash,member_id,expires_at) values(?,?,?)",hash(token),member,java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(7200)));return token;}
    private static String hash(String s){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:bytes)b.append(String.format("%02x",v));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
