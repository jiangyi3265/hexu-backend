package com.ruoyi.hexu;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;
@Service
public class HexuFinanceService {
    private final HexuStore db;
    @Autowired(required=false) private PaymentGateway payoutGateway;
    @Value("${hexu.wechat-pay.enabled:false}") private boolean realPaymentEnabled;
    /**
     * Bank remittance is intentionally a separate, opt-in path.  It never
     * uses the WeChat gateway and stays disabled unless operations explicitly
     * enable it in the server configuration.
     */
    @Value("${hexu.bank-payout.enabled:false}") private boolean bankPayoutEnabled;
    @Value("${hexu.bank-payout.require-dual-control:true}") private boolean bankPayoutRequireDualControl;
    private void holdEarnings(long line){for(Map<String,Object>e:db.jdbc().queryForList("select e.*,a.member_id from hx_earning e join hx_agent a on a.id=e.agent_id where e.line_id=? and e.status='AVAILABLE' and e.amount>e.reversed order by a.member_id,e.id",line)){Map<String,Object>w=db.one("select * from hx_wallet where shop_id=? and member_id=? for update",e.get("shop_id"),e.get("member_id"));long held=Math.min(Math.max(0,number(w,"available")),number(e,"amount")-number(e,"reversed"));if(held>0){db.jdbc().update("update hx_wallet set available=available-?,frozen=frozen+? where shop_id=? and member_id=?",held,held,e.get("shop_id"),e.get("member_id"));db.jdbc().update("update hx_earning set frozen_amount=? where id=?",held,e.get("id"));}}}
    private void releaseEarnings(long line){for(Map<String,Object>e:db.jdbc().queryForList("select e.*,a.member_id from hx_earning e join hx_agent a on a.id=e.agent_id where e.line_id=? and e.frozen_amount>0 order by a.member_id,e.id",line)){long held=number(e,"frozen_amount");require(db.jdbc().update("update hx_wallet set available=available+?,frozen=frozen-? where shop_id=? and member_id=? and frozen>=?",held,held,e.get("shop_id"),e.get("member_id"),held)==1,"售后冻结金额不一致");db.jdbc().update("update hx_earning set frozen_amount=0 where id=?",e.get("id"));}}
    public HexuFinanceService(HexuStore db){this.db=db;}
    private void restockRefund(Map<String,Object> refund,Map<String,Object> order,Map<String,Object> line){
        boolean unshipped="PAID".equals(str(order,"status"))&&Arrays.asList("REFUND_ONLY","PARTIAL").contains(str(refund,"refund_type"));
        int quantity=Math.toIntExact(unshipped?number(refund,"qty"):number(refund,"restock_qty"));
        if(unshipped)db.jdbc().update("update hx_refund set restock_qty=? where id=?",quantity,refund.get("id"));
        if(quantity>0)db.inventory(number(order,"shop_id"),str(line,"sku_id"),quantity,0,unshipped?"REFUND_UNSHIPPED":"RETURN",str(refund,"id"));
    }
    private long alreadyReclaimed(String order,long scope,long member,String key){
        long total=0;
        for(Map<String,Object>row:db.jdbc().queryForList("select r.id,a.after_json from hx_refund r left join hx_audit a on a.reference_id=r.id and a.action='REFUND_POINT_RECLAIM' where r.order_id=? and r.status='SUCCESS'",order)){
            // 旧退款没有逐笔应追记录时，沿用其实际积分日志；新退款以事务内审计记录应追数，包含待追回和过期抵扣。
            long amount=row.get("after_json")==null?db.jdbc().queryForObject("select coalesce(sum(-amount),0) from hx_point_log where shop_id=? and member_id=? and kind='REFUND_RECLAIM' and reference_id=?",Long.class,scope,member,row.get("id")):number(db.parse(row.get("after_json")),key);
            total=Math.addExact(total,amount);
        }
        return total;
    }
    private long rewardReclaim(String order,long scope,long member,long issued,long goods,long refunded,long rate,String key){
        if(issued==0)return 0;
        Map<String,Object>receipt=db.optional("select after_json from hx_audit where action='ORDER_RECEIVE' and reference_id=? order by id limit 1",order);
        long purchaseIssued=receipt==null?issued:Math.min(issued,number(db.parse(receipt.get("after_json")),key));
        long reviewIssued=issued-purchaseIssued;
        // 实际发放拆成收货奖励与评价奖励；收货前退款不会被当成已经追回的积分。
        long purchaseDue=Math.min(purchaseIssued,Math.multiplyExact(Math.max(0,goods-refunded)/100,rate));
        long reviewDue=reviewIssued-SettlementCalculator.proportion(reviewIssued,Math.min(refunded,goods),goods);
        long outstanding=issued-alreadyReclaimed(order,scope,member,scope==0?"platform":"shop");
        return Math.max(0,outstanding-Math.addExact(purchaseDue,reviewDue));
    }
    private Map<String,Object> refundPoints(Map<String,Object>r,Map<String,Object>o,Map<String,Object>line){long previous=number(line,"refunded_qty"),next=previous+number(r,"qty");require(next<=number(line,"qty"),"退款数量超限");Map<String,Object>snapshot=db.parse(line.get("snapshot_json"));long points=Math.multiplyExact(number(snapshot,"pointPrice"),number(r,"qty"));if("RETURN".equals(str(r,"refund_type")))db.defective(number(o,"shop_id"),str(line,"sku_id"),(int)(number(r,"qty")-number(r,"restock_qty")),"RETURN_DEFECTIVE",str(r,"id"));db.points(number(snapshot,"pointsScope"),number(o,"buyer_id"),points,"REFUND_RETURN",str(r,"id"),false);restockRefund(r,o,line);db.jdbc().update("update hx_order_line set refunded_qty=? where id=?",next,line.get("id"));db.jdbc().update("update hx_order set status=? where id=?",next==number(line,"qty")?"REFUNDED":str(o,"status"),o.get("id"));db.jdbc().update("update hx_refund set status='SUCCESS' where id=?",r.get("id"));db.message(number(o,"shop_id"),number(o,"buyer_id"),"兑换售后完成，积分已退回",str(r,"id"));return db.one("select * from hx_refund where id=?",r.get("id"));}
    @Transactional public Map<String,Object> transfer(Actor a,long scope,Map<String,Object> b){db.member(a);long recipient=number(b,"recipientId"),amount=number(b,"amount");require(recipient!=a.id&&amount>0&&amount<=db.parameter(scope,"points_risk","singleLimit",1000),"不能自转赠，转赠金额必须在单笔限额以内且为正整数");Map<String,Object> receiver=db.one("select * from hx_member where id=? and status='ACTIVE'",recipient);require(bool(receiver,"authorized"),"接收人尚未授权");if(scope!=0){require(db.optional("select * from hx_binding where shop_id=? and member_id=?",scope,a.id)!=null&&db.optional("select * from hx_binding where shop_id=? and member_id=?",scope,recipient)!=null,"商城积分仅可转赠本商城用户");}
        // Lock both accounts in a stable order; every mutation shares these same DB rows.
        for(long id:new long[]{Math.min(a.id,recipient),Math.max(a.id,recipient)}){db.ensurePoints(scope,id);db.one("select * from hx_points where shop_id=? and member_id=? for update",scope,id);}
        Map<String,Object>blocked=new HexuPointRisk(db).preflight(a,scope);if(blocked!=null)return blocked;require(!bool(db.one("select frozen from hx_points where shop_id=? and member_id=?",scope,recipient),"frozen"),"接收积分账户已冻结");
        long day=db.jdbc().queryForObject("select coalesce(sum(-amount),0) from hx_point_log where shop_id=? and member_id=? and kind='TRANSFER_OUT' and created_at>=?",Long.class,scope,a.id,java.sql.Timestamp.valueOf(java.time.LocalDate.now().atStartOfDay()));
        long month=db.jdbc().queryForObject("select coalesce(sum(-amount),0) from hx_point_log where shop_id=? and member_id=? and kind='TRANSFER_OUT' and created_at>=?",Long.class,scope,a.id,java.sql.Timestamp.valueOf(java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay()));
        require(day+amount<=db.parameter(scope,"points_risk","dailyLimit",5000)&&month+amount<=db.parameter(scope,"points_risk","monthlyLimit",30000),"超过积分日或月转赠限额");Map<String,Object> rp=db.one("select * from hx_points where shop_id=? and member_id=?",scope,recipient);require(number(rp,"pending_reclaim")==0,"接收人存在待追回积分，暂不可接收转赠");String ref=id("JF");db.points(scope,a.id,-amount,"TRANSFER_OUT",ref,false);db.points(scope,recipient,amount,"TRANSFER_IN",ref,false);db.message(scope,recipient,"收到积分转赠 "+amount,ref);return map("reference",ref,"debit",-amount,"credit",amount);
    }
    @Transactional public Map<String,Object> withdraw(Actor a,long shop,Map<String,Object>b){db.member(a);require(db.agent(a.id,shop)!=null||db.optional("select id from hx_document where shop_id=? and member_id=? and kind='peer_authorization' and status='ACTIVE'",shop,a.id)!=null,"仅有效代理或获批分红受益人可申请提现");long amount=number(b,"amount");require(amount>=100,"单次最低提现1元");require(Arrays.asList("BALANCE","WECHAT","BANK").contains(str(b,"channel")),"提现渠道无效");require(!str(b,"accountRef").isEmpty()&&str(b,"accountRef").length()<=128,"请选择已验证结算账户");
        if(realPaymentEnabled){
            String channel=str(b,"channel"),merchant=str(db.shop(shop),"merchant_no");
            if("BANK".equals(channel)) require(bankPayoutEnabled,"银行卡线下打款未启用，暂不可申请");
            else require(payoutGateway!=null&&payoutGateway.payoutReady(channel,merchant),"提现转账渠道尚未开通，暂不可申请");
        }
        Map<String,Object> account=db.one("select * from hx_document where id=? and member_id=? and kind='settlement_account' and status='APPROVED'",str(b,"accountRef"),a.id);require(number(account,"shop_id")==shop,"结算账户所属商城错误");require(str(b,"channel").equals(str(db.parse(account.get("body_json")),"channel")),"提现渠道与已审核结算账户不一致");db.ensureWallet(shop,a.id);Map<String,Object>w=db.one("select * from hx_wallet where shop_id=? and member_id=? for update",shop,a.id);require(number(w,"available")>=amount,"可提现余额不足");long fee=SettlementCalculator.proportion(amount,60,10000);String ref=id("TX");db.jdbc().update("update hx_wallet set available=available-?,frozen=frozen+? where shop_id=? and member_id=?",amount,amount,shop,a.id);db.jdbc().update("insert into hx_withdrawal(id,shop_id,member_id,amount,fee,net,channel,account_ref,status) values(?,?,?,?,?,?,?,?,'PENDING')",ref,shop,a.id,amount,fee,amount-fee,str(b,"channel"),str(b,"accountRef"));db.jdbc().update("update hx_withdrawal set merchant_no=? where id=?",db.one("select merchant_no from hx_shop where id=?",shop).get("merchant_no"),ref);db.audit(a,shop,"WITHDRAW_APPLY",ref,null,map("amount",amount,"fee",fee));return db.one("select * from hx_withdrawal where id=?",ref);
    }
    @Transactional public Map<String,Object> reviewWithdrawal(Actor a,String id,boolean approve,String reason){Map<String,Object>w=db.one("select * from hx_withdrawal where id=? for update",id);db.manage(a,number(w,"shop_id"),"OWNER","FINANCE");require(a.id!=number(w,"member_id"),"申请人不能审核自己的提现");require("PENDING".equals(str(w,"status")),"申请已审核");require(approve||reason.trim().length()>=2,"拒绝必须填写原因");
        if(approve&&realPaymentEnabled){
            if("BANK".equals(str(w,"channel"))) require(bankPayoutEnabled,"银行卡线下打款未启用，请驳回并释放冻结余额");
            else {String merchant=str(w,"merchant_no");require(payoutGateway!=null&&payoutGateway.payoutReady(str(w,"channel"),merchant),"提现转账渠道尚未开通，请驳回并释放冻结余额");}
        }
        db.jdbc().update("update hx_withdrawal set status=?,reviewer=?,reviewed_at=CURRENT_TIMESTAMP,reason=? where id=?",approve?"APPROVED":"REJECTED",a.id,reason,id);if(!approve)release(w);db.audit(a,number(w,"shop_id"),"WITHDRAW_REVIEW",id,w,map("approve",approve,"reason",reason));return db.one("select * from hx_withdrawal where id=?",id);}
    private void release(Map<String,Object>w){require(db.jdbc().update("update hx_wallet set available=available+?,frozen=frozen-? where shop_id=? and member_id=? and frozen>=?",w.get("amount"),w.get("amount"),w.get("shop_id"),w.get("member_id"),w.get("amount"))==1,"冻结余额不一致");}
    @Transactional public Map<String,Object> payoutResult(String id,boolean success,String channelRef,String reason){
        Map<String,Object>w=db.one("select * from hx_withdrawal where id=? for update",id);
        // A bank withdrawal in real-payment mode must go through the manual
        // evidence + dual-control operation below, never a generic callback.
        require(!(realPaymentEnabled&&"BANK".equals(str(w,"channel"))),"银行卡提现必须登记线下打款结果");
        if(Arrays.asList("PAID","FAILED").contains(str(w,"status"))){require(success=="PAID".equals(str(w,"status")),"渠道结果与已有终态冲突，请人工对账");return w;}
        require(Arrays.asList("APPROVED","PROCESSING").contains(str(w,"status")),"提现尚未审批");require(!success||!channelRef.isEmpty(),"到账必须有渠道流水");require(success||!reason.isEmpty(),"付款失败必须记录原因");if(success){require(db.jdbc().update("update hx_wallet set frozen=frozen-? where shop_id=? and member_id=? and frozen>=?",w.get("amount"),w.get("shop_id"),w.get("member_id"),w.get("amount"))==1,"冻结余额不一致");db.jdbc().update("insert into hx_ledger(shop_id,member_id,category,amount,reference_id) values(?,?,'PAYOUT',?,?)",w.get("shop_id"),w.get("member_id"),-number(w,"amount"),id);}else release(w);db.jdbc().update("update hx_withdrawal set status=?,channel_ref=?,reason=? where id=?",success?"PAID":"FAILED",channelRef,reason,id);db.message(number(w,"shop_id"),number(w,"member_id"),success?"提现已到账":"付款失败，已退回余额",id);return db.one("select * from hx_withdrawal where id=?",id);
    }

    /**
     * Record the result of an explicitly enabled manual bank remittance.
     * Review approval only leaves funds frozen; this operation is the only
     * real-mode BANK transition to PAID/FAILED and always records the actor,
     * external bank reference and encrypted-evidence identifiers.
     */
    @Transactional public Map<String,Object> bankPayoutResult(Actor a,String id,boolean success,Map<String,Object> input){
        Map<String,Object>w=db.one("select * from hx_withdrawal where id=? for update",id);
        require(a.platform||(a.staff&&"FINANCE".equals(str(db.one("select role_code from hx_staff where user_id=? and shop_id=?",a.id,w.get("shop_id")),"role_code"))),"仅财务岗位可登记银行卡打款结果");
        db.manage(a,number(w,"shop_id"),"OWNER","FINANCE");
        require("BANK".equals(str(w,"channel")),"仅银行卡提现支持线下打款登记");
        String reference=str(input,"bankReference");if(reference.isEmpty())reference=str(input,"channelRef");
        String note=str(input,"reason").trim();
        List<?>files=input.get("evidenceIds") instanceof List?(List<?>)input.get("evidenceIds"):
            input.get("uploads") instanceof List?(List<?>)input.get("uploads"):Collections.emptyList();
        if(Arrays.asList("PAID","FAILED").contains(str(w,"status"))){
            require(success=="PAID".equals(str(w,"status"))&&reference.equals(str(w,"channel_ref")),"线下打款结果与已有终态冲突，请人工对账");
            return w;
        }
        require(bankPayoutEnabled,"银行卡线下打款未启用");
        require(Arrays.asList("APPROVED","PROCESSING").contains(str(w,"status")),"提现尚未审核通过");
        require(reference.matches("[A-Za-z0-9_./:-]{4,100}"),"请填写有效的银行外部流水号");
        require(db.optional("select id from hx_withdrawal where channel='BANK' and channel_ref=? and id<>?",reference,id)==null,"银行外部流水号已用于其他提现");
        require(note.length()>=2&&note.length()<=500,success?"请记录打款备注":"请填写银行失败原因");
        require(files.size()>=1&&files.size()<=9,"请上传1至9份银行回单或失败凭证");
        for(Object file:files){String evidenceId=String.valueOf(file);require(evidenceId.matches("[A-Za-z0-9_-]{1,64}")&&db.optional("select id from hx_attachment where id=? and shop_id=? and purpose='FINANCE' and member_id=?",evidenceId,w.get("shop_id"),a.id)!=null,"银行凭证不存在、用途不符或不属于当前操作人");}
        if(bankPayoutRequireDualControl&&number(w,"reviewer")>0)require(a.id!=number(w,"reviewer"),"线下打款登记必须由不同于初审人的财务人员操作");
        Map<String,Object>before=new LinkedHashMap<>(w);
        if(success){require(db.jdbc().update("update hx_wallet set frozen=frozen-? where shop_id=? and member_id=? and frozen>=?",w.get("amount"),w.get("shop_id"),w.get("member_id"),w.get("amount"))==1,"冻结余额不一致");db.jdbc().update("insert into hx_ledger(shop_id,member_id,category,amount,reference_id) values(?,?,'PAYOUT',?,?)",w.get("shop_id"),w.get("member_id"),-number(w,"amount"),id);}else release(w);
        db.jdbc().update("update hx_withdrawal set status=?,channel_ref=?,payout_operator=?,payout_evidence_json=?,payout_reason=?,payout_recorded_at=CURRENT_TIMESTAMP where id=?",success?"PAID":"FAILED",reference,a.id,db.json(map("files",files)),note,id);
        Map<String,Object>after=db.one("select * from hx_withdrawal where id=?",id);
        db.audit(a,number(w,"shop_id"),"BANK_PAYOUT_RESULT",id,before,after);
        db.message(number(w,"shop_id"),number(w,"member_id"),success?"银行卡提现已登记到账":"银行卡打款失败，已退回余额",id);
        return after;
    }
    @Transactional public Map<String,Object> refundApply(Actor a,String order,Map<String,Object>b){db.member(a);Map<String,Object> o=db.one("select * from hx_order where id=? for update",order);require(number(o,"buyer_id")==a.id,"无权申请此订单售后");new HexuGroupPurchase(db).fulfillmentAllowed(order);require(Arrays.asList("PAID","SHIPPED","COMPLETED").contains(str(o,"status")),"订单不可申请退款");String type=str(b,"type");require(Arrays.asList("REFUND_ONLY","RETURN","PARTIAL","EXCHANGE").contains(type),"售后类型错误");require(str(b,"reason").trim().length()>=2,"请填写退款原因");Map<String,Object> l=db.one("select * from hx_order_line where id=? and order_id=? for update",number(b,"lineId"),order);if(o.get("completed_at")!=null){Map<String,Object>snapshot=db.parse(l.get("snapshot_json"));long days=snapshot.containsKey("afterSalesDays")?number(snapshot,"afterSalesDays"):7;require(((java.sql.Timestamp)o.get("completed_at")).getTime()+days*86400000L>=System.currentTimeMillis(),"已超过订单快照约定的售后申请期限");}long pending=db.jdbc().queryForObject("select coalesce(sum(qty),0) from hx_refund where line_id=? and status not in ('SUCCESS','REJECTED','CLOSED')",Long.class,l.get("id"));require(pending==0,"该商品已有进行中的售后，请处理完成后再申请");int qty=Math.toIntExact(number(b,"qty"));require(qty>0&&qty+pending+number(l,"refunded_qty")<=number(l,"qty"),"超过可售后数量");long target=SettlementCalculator.proportion(number(l,"paid"),number(l,"refunded_qty")+qty,number(l,"qty"));long amount=target-number(l,"refunded_paid");List<?>uploads=b.get("uploads") instanceof List?(List<?>)b.get("uploads"):Collections.emptyList();require(uploads.size()<=9&&str(b,"description").length()<=500,"凭证最多9份，描述最多500字");for(Object file:uploads)require(db.optional("select id from hx_attachment where id=? and shop_id=? and member_id=?",file,o.get("shop_id"),a.id)!=null,"售后凭证不存在或不属于申请人");String ref=id("SH");db.jdbc().update("insert into hx_refund(id,order_id,line_id,shop_id,member_id,qty,amount,refund_type,status,reason) values(?,?,?,?,?,?,?,?,'PENDING',?)",ref,order,l.get("id"),o.get("shop_id"),a.id,qty,amount,type,str(b,"reason"));db.jdbc().update("update hx_refund set evidence_json=? where id=?",db.json(map("description",str(b,"description"),"uploads",uploads)),ref);holdEarnings(number(l,"id"));db.audit(a,number(o,"shop_id"),"REFUND_APPLY",ref,null,b);return db.one("select * from hx_refund where id=?",ref);}
    @Transactional public Map<String,Object> refundReview(Actor a,String id,Map<String,Object>b){Map<String,Object>r=db.one("select * from hx_refund where id=? for update",id);db.manage(a,number(r,"shop_id"),"SUPPORT");require("PENDING".equals(str(r,"status")),"售后已审核");boolean approve=bool(b,"approve");require(approve||!str(b,"reason").trim().isEmpty(),"请填写驳回原因");String status=!approve?"REJECTED":"RETURN".equals(str(r,"refund_type"))||"EXCHANGE".equals(str(r,"refund_type"))?"WAIT_RETURN":"APPROVED";db.jdbc().update("update hx_refund set status=?,review_by=?,review_note=? where id=?",status,a.id,str(b,"reason"),id);if(!approve)releaseEarnings(number(r,"line_id"));db.audit(a,number(r,"shop_id"),"REFUND_REVIEW",id,r,b);if("APPROVED".equals(status)&&"POINTS".equals(str(db.one("select order_type from hx_order where id=?",r.get("order_id")),"order_type")))return refundConfirmed(id);return db.one("select * from hx_refund where id=?",id);}
    @Transactional public Map<String,Object> inspect(Actor a,String id,Map<String,Object>b){Map<String,Object>r=db.one("select * from hx_refund where id=? for update",id);db.manage(a,number(r,"shop_id"),"WAREHOUSE");require(!b.containsKey("receivedQty")||number(b,"receivedQty")==number(r,"qty"),"收到数量与申请不符，请先处理退货数量差异");List<?>files=b.get("uploads") instanceof List?(List<?>)b.get("uploads"):Collections.emptyList();require(files.size()<=9&&str(b,"note").length()<=500,"验收凭证或备注超过限制");for(Object file:files)require(db.optional("select id from hx_attachment where id=? and shop_id=? and member_id=?",file,r.get("shop_id"),a.id)!=null,"验收凭证不属于当前操作人");inspect(a,id,(int)number(b,"goodQty"));Map<String,Object>evidence=db.parse(r.get("evidence_json"));evidence.put("inspection",map("receivedQty",r.get("qty"),"goodQty",number(b,"goodQty"),"note",str(b,"note"),"uploads",files,"inspector",a.id));db.jdbc().update("update hx_refund set evidence_json=? where id=?",db.json(evidence),id);return db.one("select * from hx_refund where id=?",id);}
    @Transactional public Map<String,Object> inspect(Actor a,String id,int goodQty){Map<String,Object>r=db.one("select * from hx_refund where id=? for update",id);db.manage(a,number(r,"shop_id"),"WAREHOUSE");require("WAIT_RETURN".equals(str(r,"status")),"不是待退货验收状态");require(goodQty>=0&&goodQty<=number(r,"qty"),"验收合格数量有误");db.jdbc().update("update hx_refund set restock_qty=?,status=? where id=?",goodQty,"EXCHANGE".equals(str(r,"refund_type"))?"WAIT_EXCHANGE":"APPROVED",id);db.audit(a,number(r,"shop_id"),"RETURN_INSPECT",id,null,map("goodQty",goodQty));if(!"EXCHANGE".equals(str(r,"refund_type"))&&"POINTS".equals(str(db.one("select order_type from hx_order where id=?",r.get("order_id")),"order_type")))return refundConfirmed(id);return db.one("select * from hx_refund where id=?",id);}
    @Transactional public Map<String,Object> refundConfirmed(String id){Map<String,Object>r=db.one("select * from hx_refund where id=? for update",id);if("SUCCESS".equals(str(r,"status")))return r;require("APPROVED".equals(str(r,"status")),"退款未审核或未验收");String order=str(r,"order_id");Map<String,Object>o=db.one("select * from hx_order where id=? for update",order);Map<String,Object>l=db.one("select * from hx_order_line where id=? for update",r.get("line_id"));if("POINTS".equals(str(o,"order_type")))return refundPoints(r,o,l);long cumulativeQty=number(l,"refunded_qty")+number(r,"qty");require(cumulativeQty<=number(l,"qty"),"退款数量超限");long newPaid=SettlementCalculator.proportion(number(l,"paid"),cumulativeQty,number(l,"qty"));long amount=newPaid-number(l,"refunded_paid");require(amount==number(r,"amount"),"退款金额发生变化，请重新核对渠道金额");
        releaseEarnings(number(l,"id"));
        for(Map<String,Object>e:db.jdbc().queryForList("select e.*,a.member_id from hx_earning e join hx_agent a on a.id=e.agent_id where line_id=? order by a.member_id,e.id",l.get("id"))){long target=SettlementCalculator.proportion(number(e,"amount"),cumulativeQty,number(l,"qty"));long delta=target-number(e,"reversed");if("AVAILABLE".equals(str(e,"status")))db.ledger(number(e,"shop_id"),number(e,"member_id"),-delta,"REFUND_"+str(e,"earning_type"),id);db.jdbc().update("update hx_earning set reversed=? where id=?",target,e.get("id"));}
        if("RETURN".equals(str(r,"refund_type")))db.defective(number(r,"shop_id"),str(l,"sku_id"),(int)(number(r,"qty")-number(r,"restock_qty")),"RETURN_DEFECTIVE",id);
        long previous=number(o,"refunded"),next=previous+amount,goods=number(o,"total")-number(o,"freight");
        long used=number(o,"points_used");
        long returned=SettlementCalculator.proportion(used,next,goods)-SettlementCalculator.proportion(used,previous,goods);
        if(returned>0)db.points(number(o,"points_scope"),number(o,"buyer_id"),returned,"REFUND_RETURN",id,false);
        Map<String,Object>rewardSnapshot=db.parse(db.one("select snapshot_json from hx_order_line where order_id=? order by id limit 1",order).get("snapshot_json"));
        long platformRate=rewardSnapshot.containsKey("pointsRate")?number(rewardSnapshot,"pointsRate"):1;
        long reclaimed=rewardReclaim(order,0,number(o,"buyer_id"),number(o,"points_earned"),goods,next,platformRate,"pointsEarned");
        if(reclaimed>0)db.points(0,number(o,"buyer_id"),-reclaimed,"REFUND_RECLAIM",id,true);
        long shopReclaimed=rewardReclaim(order,number(o,"shop_id"),number(o,"buyer_id"),number(o,"shop_points_earned"),goods,next,number(rewardSnapshot,"shopPointsRate"),"shopPointsEarned");
        if(shopReclaimed>0)db.points(number(o,"shop_id"),number(o,"buyer_id"),-shopReclaimed,"REFUND_RECLAIM",id,true);
        db.audit(new Actor(0,true,true),number(o,"shop_id"),"REFUND_POINT_RECLAIM",id,null,map("platform",reclaimed,"shop",shopReclaimed));
        restockRefund(r,o,l);
        db.jdbc().update("update hx_order_line set refunded_qty=?,refunded_paid=? where id=?",cumulativeQty,newPaid,l.get("id"));db.jdbc().update("update hx_order set refunded=?,status=? where id=?",next,next>=goods?"REFUNDED":str(o,"status"),order);db.jdbc().update("update hx_refund set status='SUCCESS' where id=?",id);db.jdbc().update("insert into hx_ledger(shop_id,member_id,category,amount,reference_id) values(?,0,'REFUND',?,?)",o.get("shop_id"),-amount,id);new HexuInvitationRewards(db).reversed(order);new HexuLinkCampaign(db).reversed(order);db.message(number(o,"shop_id"),number(o,"buyer_id"),"退款成功",id);return db.one("select * from hx_refund where id=?",id);
    }
    @Transactional public Map<String,Object> returnTracking(Actor a,String id,Map<String,Object>b){
        db.member(a);
        Map<String,Object> r=db.one("select * from hx_refund where id=? for update",id);
        require(!a.staff&&number(r,"member_id")==a.id,"无权提交退货物流");
        require("WAIT_RETURN".equals(str(r,"status")),"当前售后不需要寄回商品");
        String carrier=str(b,"carrier").trim(),tracking=str(b,"tracking");
        require(b.get("carrier") instanceof String&&!carrier.isEmpty()&&carrier.codePointCount(0,carrier.length())<=60&&b.get("tracking") instanceof String&&tracking.matches("[A-Za-z0-9]{8,40}"),"请填写有效退货运单");
        Map<String,Object> previous=r.get("return_json")==null?Collections.emptyMap():db.parse(r.get("return_json"));
        Object raw=b.containsKey("uploads")?b.get("uploads"):previous.getOrDefault("uploads",Collections.emptyList());
        require(raw instanceof List&&((List<?>)raw).size()<=9,"退货运单凭证最多9张图片");
        List<String> uploads=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Object file:(List<?>)raw){
            require(file instanceof String&&!((String)file).isEmpty()&&seen.add((String)file),"退货运单凭证不能重复或为空");
            require(db.optional("select id from hx_attachment where id=? and shop_id=? and member_id=? and purpose='RETURN'",file,number(r,"shop_id"),a.id)!=null,"退货运单凭证须为本人在本商城上传的退货图片");uploads.add((String)file);
        }
        Map<String,Object> saved=map("carrier",carrier,"tracking",tracking,"uploads",uploads);
        db.jdbc().update("update hx_refund set return_json=? where id=?",db.json(saved),id);
        db.audit(a,number(r,"shop_id"),"RETURN_TRACKING",id,previous,saved);return db.one("select * from hx_refund where id=?",id);
    }
    @Transactional public Map<String,Object> cancelRefund(Actor a,String id){
        Map<String,Object> r=db.one("select * from hx_refund where id=? for update",id);
        require(!a.staff&&number(r,"member_id")==a.id,"无权撤销售后");
        String status=str(r,"status");
        require(!"CLOSED".equals(status),"售后申请已关闭，不能重复撤销");
        require(Arrays.asList("PENDING","WAIT_RETURN").contains(status),"当前售后状态不可撤销");
        require(r.get("return_json")==null,"退货运单已提交，不能撤销");
        db.jdbc().update("update hx_refund set status='CLOSED' where id=?",id);releaseEarnings(number(r,"line_id"));db.audit(a,number(r,"shop_id"),"REFUND_CANCEL",id,r,null);return db.one("select * from hx_refund where id=?",id);
    }
    @Transactional public Map<String,Object> exchangeShip(Actor a,String id,Map<String,Object>b){
        Map<String,Object> r=db.one("select * from hx_refund where id=? for update",id);long shop=number(r,"shop_id");db.manage(a,shop,"WAREHOUSE");
        require("WAIT_EXCHANGE".equals(str(r,"status")),"换货尚未完成验收");
        require(!str(b,"carrier").isEmpty()&&str(b,"tracking").matches("[A-Za-z0-9]{8,40}"),"请填写有效换货运单");
        Map<String,Object> line=db.one("select * from hx_order_line where id=?",r.get("line_id"));
        if(number(r,"restock_qty")>0)db.inventory(shop,str(line,"sku_id"),(int)number(r,"restock_qty"),0,"EXCHANGE_RETURN",id);
        db.inventory(shop,str(line,"sku_id"),-(int)number(r,"qty"),0,"EXCHANGE_SHIP",id);db.defective(shop,str(line,"sku_id"),(int)(number(r,"qty")-number(r,"restock_qty")),"EXCHANGE_DEFECTIVE",id);
        db.jdbc().update("update hx_refund set status='EXCHANGE_SHIPPED',exchange_json=? where id=?",db.json(map("carrier",b.get("carrier"),"tracking",b.get("tracking"))),id);
        db.audit(a,shop,"EXCHANGE_SHIP",id,r,b);db.message(shop,number(r,"member_id"),"换货商品已发出",id);return db.one("select * from hx_refund where id=?",id);
    }
    @Transactional public Map<String,Object> exchangeReceive(Actor a,String id){
        Map<String,Object> r=db.one("select * from hx_refund where id=? for update",id);require(!a.staff&&number(r,"member_id")==a.id,"无权确认换货收货");
        if("EXCHANGE".equals(str(r,"refund_type"))&&"CLOSED".equals(str(r,"status"))&&r.get("exchange_json")!=null)
            throw new BusinessException("换货已完成，无需重复确认");
        require("EXCHANGE_SHIPPED".equals(str(r,"status")),"换货商品尚未发出");db.jdbc().update("update hx_refund set status='CLOSED' where id=?",id);releaseEarnings(number(r,"line_id"));db.audit(a,number(r,"shop_id"),"EXCHANGE_RECEIVE",id,r,null);return db.one("select * from hx_refund where id=?",id);
    }
    @Transactional public Map<String,Object> account(Actor a,long shop){db.member(a);db.pointAccount(0,a.id);db.pointAccount(shop,a.id);Map<String,Object>w=db.optional("select * from hx_wallet where shop_id=? and member_id=?",shop,a.id),p=db.optional("select * from hx_points where shop_id=0 and member_id=?",a.id),sp=db.optional("select * from hx_points where shop_id=? and member_id=?",shop,a.id);return map("summary",db.one("select coalesce(sum(case when e.status='PENDING' then e.amount-e.reversed else 0 end),0) pendingEarnings,coalesce(sum(case when e.status='AVAILABLE' then e.amount-e.reversed else 0 end),0) settledEarnings from hx_earning e join hx_agent a on a.id=e.agent_id where e.shop_id=? and a.member_id=?",shop,a.id),"withdrawnTotal",db.jdbc().queryForObject("select coalesce(sum(amount),0) from hx_withdrawal where shop_id=? and member_id=? and status='PAID'",Long.class,shop,a.id),"pointRisk",map("platformSingle",db.parameter(0,"points_risk","singleLimit",1000),"shopSingle",db.parameter(shop,"points_risk","singleLimit",1000)),"wallet",w==null?map("available",0,"frozen",0):w,"platformPoints",db.pointSnapshot(0,a.id),"shopPoints",db.pointSnapshot(shop,a.id),"earnings",db.jdbc().queryForList("select e.* from hx_earning e join hx_agent a on a.id=e.agent_id where e.shop_id=? and a.member_id=? order by e.id desc limit 200",shop,a.id),"ledger",db.jdbc().queryForList("select * from hx_ledger where shop_id=? and member_id=? order by id desc limit 200",shop,a.id),"pointLedger",db.jdbc().queryForList("select * from hx_point_log where member_id=? and shop_id in (0,?) order by id desc limit 200",a.id,shop),"withdrawals",db.jdbc().queryForList("select * from hx_withdrawal where shop_id=? and member_id=? order by created_at desc",shop,a.id),"refunds",new HexuReadModels(db).refunds(a,shop,false));}
}
