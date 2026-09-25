package com.ruoyi.hexu;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Durable inbox for verified channel events, including events awaiting reconciliation. */
@Service
public class HexuChannelInbox {
 private final HexuStore db;private final HexuOrderService orders;private final HexuFinanceService finance;
 public HexuChannelInbox(HexuStore db,HexuOrderService orders,HexuFinanceService finance){this.db=db;this.orders=orders;this.finance=finance;}
 @Transactional public String accept(Map<String,Object>event){
  String type=str(event,"type"),id=str(event,"id"),reference=str(event,"channelRef");require(Arrays.asList("PAYMENT_SUCCESS","REFUND_SUCCESS","PAYOUT_SUCCESS","PAYOUT_FAILED").contains(type),"不支持的渠道事件");require(!id.isEmpty()&&!reference.isEmpty()&&!str(event,"merchantNo").isEmpty(),"渠道事件缺少业务单号、商户号或渠道流水");
  Map<String,Object>safe=map("type",type,"id",id,"channelRef",reference,"merchantNo",str(event,"merchantNo"),"amount",number(event,"amount"),"reason",str(event,"reason"));String key=DigestUtils.md5DigestAsHex((type+":"+id+":"+reference).getBytes(StandardCharsets.UTF_8));
  Map<String,Object>business="PAYMENT_SUCCESS".equals(type)?db.optional("select o.shop_id from hx_payment p join hx_order o on o.id=p.order_id where p.id=?",id):db.optional("select shop_id from "+("REFUND_SUCCESS".equals(type)?"hx_refund":"hx_withdrawal")+" where id=?",id);
  try{db.jdbc().update("insert into hx_channel_event(id,shop_id,event_type,business_id,channel_ref,payload_json,status) values(?,?,?,?,?,?,'RECEIVED')",key,business==null?0:number(business,"shop_id"),type,id,reference,db.json(safe));}catch(DuplicateKeyException duplicate){Map<String,Object>old=db.parse(db.one("select payload_json from hx_channel_event where id=?",key).get("payload_json"));require(str(old,"merchantNo").equals(str(safe,"merchantNo"))&&number(old,"amount")==number(safe,"amount"),"重复渠道事件金额或商户不一致");}
  return key;
 }
 @Transactional public Object apply(String key){
  Map<String,Object>record=db.one("select * from hx_channel_event where id=? for update",key);if("APPLIED".equals(str(record,"status")))return map("status","APPLIED","duplicate",true);
  require(db.jdbc().queryForObject("select count(*) from hx_channel_event where event_type=? and business_id=? and status='APPLIED' and id<>?",Long.class,record.get("event_type"),record.get("business_id"),key)==0,"同一业务出现不同渠道流水，请人工对账");
  Map<String,Object>event=db.parse(record.get("payload_json"));String type=str(event,"type"),id=str(event,"id"),merchant=str(event,"merchantNo"),reference=str(event,"channelRef");Object result;
  if("PAYMENT_SUCCESS".equals(type))result=orders.paymentConfirmed(id,merchant,number(event,"amount"),reference);
  else if("REFUND_SUCCESS".equals(type)){Map<String,Object>r=db.one("select r.*,o.merchant_no from hx_refund r join hx_order o on o.id=r.order_id where r.id=?",id);require(merchant.equals(str(r,"merchant_no"))&&number(event,"amount")==number(r,"amount"),"退款商户或金额不匹配");result=finance.refundConfirmed(id);}
  else {Map<String,Object>w=db.one("select w.*,coalesce(w.merchant_no,s.merchant_no) as merchant_no from hx_withdrawal w join hx_shop s on s.id=w.shop_id where w.id=?",id);require(merchant.equals(str(w,"merchant_no"))&&number(event,"amount")==number(w,"net"),"转账商户或净额不匹配");result=finance.payoutResult(id,"PAYOUT_SUCCESS".equals(type),reference,str(event,"reason"));}
  db.jdbc().update("update hx_channel_event set status='APPLIED',error_message=null,processed_at=CURRENT_TIMESTAMP where id=?",key);return result;
 }
 @Transactional public void failed(String key,String reason){db.jdbc().update("update hx_channel_event set status='ERROR',error_message=? where id=? and status<>'APPLIED'",reason==null?"渠道处理失败":reason.substring(0,Math.min(reason.length(),500)),key);}
 public void authorize(Actor a,String key){Map<String,Object>r=db.one("select shop_id from hx_channel_event where id=?",key);if(number(r,"shop_id")==0)db.platform(a);else db.manage(a,number(r,"shop_id"),"FINANCE");}
}
