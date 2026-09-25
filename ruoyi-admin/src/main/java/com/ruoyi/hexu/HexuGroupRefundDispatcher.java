package com.ruoyi.hexu;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;

/** Stable refund IDs are the gateway idempotency keys; a timeout may be retried, never paid twice. */
@Service public class HexuGroupRefundDispatcher {
 private final HexuStore db;private final PaymentGateway gateway;private final TransactionTemplate tx;
 public HexuGroupRefundDispatcher(HexuStore db,PaymentGateway gateway,PlatformTransactionManager manager){this.db=db;this.gateway=gateway;this.tx=new TransactionTemplate(manager);}
 @Scheduled(fixedDelay=60000,initialDelay=60000) public void tick(){
  for(Map<String,Object>candidate:db.jdbc().queryForList("select refund_id from hx_group_refund where status in ('PENDING','RETRY','PROCESSING') and next_attempt<=CURRENT_TIMESTAMP order by refund_id limit 50")){
   String id=str(candidate,"refund_id");Boolean claimed=tx.execute(status->{Map<String,Object>job=db.one("select * from hx_group_refund where refund_id=? for update",id);if(!Arrays.asList("PENDING","RETRY","PROCESSING").contains(str(job,"status"))||((java.sql.Timestamp)job.get("next_attempt")).getTime()>System.currentTimeMillis())return false;Map<String,Object>refund=db.one("select * from hx_refund where id=?",id);if("SUCCESS".equals(str(refund,"status"))){db.jdbc().update("update hx_group_refund set status='SUCCESS',last_error=null,updated_at=CURRENT_TIMESTAMP where refund_id=?",id);return false;}db.jdbc().update("update hx_group_refund set status='PROCESSING',attempts=attempts+1,next_attempt=?,updated_at=CURRENT_TIMESTAMP where refund_id=?",new java.sql.Timestamp(System.currentTimeMillis()+300000),id);return true;});if(!Boolean.TRUE.equals(claimed))continue;
   try{Map<String,Object>r=db.one("select * from hx_refund where id=?",id),o=db.one("select merchant_no from hx_order where id=?",r.get("order_id"));gateway.submitRefund(r,str(o,"merchant_no"));db.jdbc().update("update hx_group_refund set status='SUBMITTED',last_error=null,updated_at=CURRENT_TIMESTAMP where refund_id=? and status='PROCESSING'",id);}
   catch(RuntimeException e){String message=e.getMessage()==null?"退款渠道提交失败":e.getMessage();db.jdbc().update("update hx_group_refund set status='RETRY',last_error=?,updated_at=CURRENT_TIMESTAMP where refund_id=? and status='PROCESSING'",message.substring(0,Math.min(1000,message.length())),id);}
  }
  db.jdbc().update("update hx_group_refund set status='SUCCESS',last_error=null,updated_at=CURRENT_TIMESTAMP where status<>'SUCCESS' and refund_id in (select id from hx_refund where status='SUCCESS')");
 }
}
