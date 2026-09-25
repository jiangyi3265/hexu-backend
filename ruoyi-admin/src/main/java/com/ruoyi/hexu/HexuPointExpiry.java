package com.ruoyi.hexu;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.Map;
import static com.ruoyi.hexu.HexuStore.*;
@Service
public class HexuPointExpiry {
 private final HexuStore db;
 public HexuPointExpiry(HexuStore db){this.db=db;}
 @Scheduled(fixedDelay=60000,initialDelay=40000) public void run(){for(Map<String,Object>row:db.jdbc().queryForList("select distinct shop_id,member_id from hx_point_batch where remaining>0 and expires_at<=CURRENT_TIMESTAMP order by shop_id,member_id limit 200"))try{db.pointAccount(number(row,"shop_id"),number(row,"member_id"));}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Point expiry failed for scope {} member {}",row.get("shop_id"),row.get("member_id"),e);}}
}
