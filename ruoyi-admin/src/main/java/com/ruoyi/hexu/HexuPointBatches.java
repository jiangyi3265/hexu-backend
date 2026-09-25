package com.ruoyi.hexu;

import java.sql.Timestamp;
import java.util.*;
import static com.ruoyi.hexu.HexuStore.*;
import static com.ruoyi.hexu.BusinessException.require;

/** Called inside the caller's transaction. Account rows serialize all batch mutations. */
public final class HexuPointBatches {
    private final HexuStore db;
    public HexuPointBatches(HexuStore db) { this.db = db; }

    public Map<String,Object> refresh(long scope,long member) {
        db.ensurePoints(scope,member);
        Map<String,Object> account=db.one("select * from hx_points where shop_id=? and member_id=? for update",scope,member);
        if(!bool(account,"batches_initialized")) {
            // Existing balances have no agreed expiry date. Preserve them as non-expiring legacy credit.
            if(number(account,"available")>0) batch(scope,member,number(account,"available"),null,"LEGACY","MIGRATION");
            db.jdbc().update("update hx_points set batches_initialized=true where shop_id=? and member_id=?",scope,member);
        }
        for(Map<String,Object>b:db.jdbc().queryForList("select * from hx_point_batch where shop_id=? and member_id=? and remaining>0 and expires_at<=CURRENT_TIMESTAMP order by id for update",scope,member)) {
            long amount=number(b,"remaining");
            db.jdbc().update("update hx_point_batch set remaining=0,expired=expired+? where id=?",amount,b.get("id"));
            change(scope,member,-amount,"EXPIRE",String.valueOf(b.get("id")));
        }
        return snapshot(scope,member);
    }

    public Map<String,Object> snapshot(long scope,long member) {
        Map<String,Object> account=db.optional("select * from hx_points where shop_id=? and member_id=?",scope,member);
        if(account==null) return map("available",0,"pending_reclaim",0,"frozen",false,"expiring",0,"expired",0,"batches",Collections.emptyList());
        long due=db.jdbc().queryForObject("select coalesce(sum(remaining),0) from hx_point_batch where shop_id=? and member_id=? and expires_at<=CURRENT_TIMESTAMP",Long.class,scope,member);
        account.put("available",Math.max(0,number(account,"available")-due));
        account.put("expiring",db.jdbc().queryForObject("select coalesce(sum(remaining),0) from hx_point_batch where shop_id=? and member_id=? and expires_at>CURRENT_TIMESTAMP and expires_at<=?",Long.class,scope,member,new Timestamp(System.currentTimeMillis()+30L*86400000)));
        account.put("expired",db.jdbc().queryForObject("select coalesce(sum(expired),0) from hx_point_batch where shop_id=? and member_id=?",Long.class,scope,member)+due);
        account.put("batches",db.jdbc().queryForList("select id,remaining,expires_at,origin_kind from hx_point_batch where shop_id=? and member_id=? and remaining>0 and (expires_at is null or expires_at>CURRENT_TIMESTAMP) order by case when expires_at is null then 1 else 0 end,expires_at,id limit 200",scope,member));
        account.remove("batches_initialized");
        return account;
    }

    private Timestamp expiry(long days) { return days==0?null:new Timestamp(Math.addExact(System.currentTimeMillis(),Math.multiplyExact(days,86400000L))); }
    private void batch(long scope,long member,long amount,Timestamp expires,String kind,String ref) {
        if(amount>0)db.jdbc().update("insert into hx_point_batch(shop_id,member_id,remaining,expires_at,origin_kind,origin_ref) values(?,?,?,?,?,?)",scope,member,amount,expires,kind,ref);
    }
    private void change(long scope,long member,long delta,String kind,String ref) {
        require(db.jdbc().update("update hx_points set available=available+? where shop_id=? and member_id=? and available+?>=0",delta,scope,member,delta)==1,"积分账本余额不一致");
        db.jdbc().update("insert into hx_point_log(shop_id,member_id,amount,kind,reference_id) values(?,?,?,?,?)",scope,member,delta,kind,ref);
    }

    public void mutate(long scope,long member,long delta,String kind,String ref,boolean reclaim,Long expiryDays) {
        Map<String,Object>account=refresh(scope,member);
        require(reclaim||kind.startsWith("REFUND_")||"CANCEL_RETURN".equals(kind)||!bool(account,"frozen"),"积分账户已冻结");
        if(delta<0) {
            long wanted=Math.negateExact(delta);
            if(reclaim) {
                String origin=refundOrder(ref);
                // Already expired rewards cannot be reclaimed a second time as debt.
                for(Map<String,Object>b:db.jdbc().queryForList("select * from hx_point_batch where shop_id=? and member_id=? and origin_ref=? and origin_kind in ('ORDER_REWARD','REVIEW_REWARD') and expired>reclaimed_expired order by id for update",scope,member,origin)) {
                    long skipped=Math.min(wanted,number(b,"expired")-number(b,"reclaimed_expired"));wanted-=skipped;
                    db.jdbc().update("update hx_point_batch set reclaimed_expired=reclaimed_expired+? where id=?",skipped,b.get("id"));
                }
            }
            long actual=Math.min(wanted,number(account,"available"));require(reclaim||actual==wanted,"积分余额不足或积分已过期");
            consume(scope,member,actual,kind,ref);
            if(wanted>actual)db.jdbc().update("update hx_points set pending_reclaim=pending_reclaim+? where shop_id=? and member_id=?",wanted-actual,scope,member);
            change(scope,member,-actual,kind,ref);
        } else if(delta>0) {
            if("CANCEL_RETURN".equals(kind)||"REFUND_RETURN".equals(kind)) { restore(scope,member,delta,kind,ref,account);return; }
            if("TRANSFER_IN".equals(kind)) { receiveTransfer(scope,member,delta,ref);return; }
            long debt=Math.min(delta,number(account,"pending_reclaim"));
            if(debt>0)db.jdbc().update("update hx_points set pending_reclaim=pending_reclaim-? where shop_id=? and member_id=?",debt,scope,member);
            long days=expiryDays==null?db.parameter(scope,"points_rule","expiryDays",365):expiryDays;
            require(days>=0&&days<=36500,"积分有效期须为0至36500天，0表示长期有效");
            batch(scope,member,delta-debt,expiry(days),kind,ref);change(scope,member,delta-debt,kind,ref);
        }
    }
    private void consume(long scope,long member,long amount,String kind,String ref) {
        long remaining=amount;
        for(Map<String,Object>b:db.jdbc().queryForList("select * from hx_point_batch where shop_id=? and member_id=? and remaining>0 order by case when expires_at is null then 1 else 0 end,expires_at,id for update",scope,member)) {
            long take=Math.min(remaining,number(b,"remaining"));if(take==0)break;remaining-=take;
            db.jdbc().update("update hx_point_batch set remaining=remaining-? where id=?",take,b.get("id"));
            db.jdbc().update("insert into hx_point_use(batch_id,shop_id,member_id,amount,kind,reference_id) values(?,?,?,?,?,?)",b.get("id"),scope,member,take,kind,ref);
        }
        require(remaining==0,"积分批次余额与账户不一致，请联系平台核对");
    }
    private String refundOrder(String ref) {Map<String,Object>r=db.optional("select order_id from hx_refund where id=?",ref);return r==null?ref:str(r,"order_id");}
    private void receiveTransfer(long scope,long member,long amount,String ref) {
        List<Map<String,Object>>uses=db.jdbc().queryForList("select u.amount,b.expires_at from hx_point_use u join hx_point_batch b on b.id=u.batch_id where u.shop_id=? and u.kind='TRANSFER_OUT' and u.reference_id=? order by u.id",scope,ref);
        long total=0;for(Map<String,Object>u:uses){long n=number(u,"amount");total+=n;batch(scope,member,n,(Timestamp)u.get("expires_at"),"TRANSFER_IN",ref);}
        require(total==amount,"转赠双方批次不一致");change(scope,member,amount,"TRANSFER_IN",ref);
    }
    private void restore(long scope,long member,long amount,String kind,String ref,Map<String,Object>account) {
        String order="CANCEL_RETURN".equals(kind)?ref:refundOrder(ref);long left=amount,credited=0,expired=0;
        List<Map<String,Object>> uses=db.jdbc().queryForList("select u.*,b.expires_at from hx_point_use u join hx_point_batch b on b.id=u.batch_id where u.shop_id=? and u.member_id=? and u.reference_id=? and u.kind in ('ORDER_USE','REDEMPTION') and u.amount>u.restored order by u.id for update",scope,member,order);
        for(Map<String,Object>u:uses){long n=Math.min(left,number(u,"amount")-number(u,"restored"));if(n==0)break;left-=n;Timestamp at=(Timestamp)u.get("expires_at");
            db.jdbc().update("update hx_point_use set restored=restored+? where id=?",n,u.get("id"));
            if(at!=null&&at.getTime()<=System.currentTimeMillis()){expired+=n;db.jdbc().update("update hx_point_batch set expired=expired+? where id=?",n,u.get("batch_id"));}
            else {credited+=n;db.jdbc().update("update hx_point_batch set remaining=remaining+? where id=?",n,u.get("batch_id"));}
        }
        if(left>0){require(uses.isEmpty()&&db.jdbc().queryForObject("select count(*) from hx_point_use where shop_id=? and member_id=? and reference_id=?",Long.class,scope,member,order)==0,"积分返还数量超出实际使用数量");batch(scope,member,left,null,"LEGACY_RETURN",order);credited+=left;}
        change(scope,member,credited,kind,ref);
        if(expired>0)db.jdbc().update("insert into hx_point_log(shop_id,member_id,amount,kind,reference_id) values(?,?,0,'RETURN_EXPIRED',?)",scope,member,ref);
        long debt=Math.min(credited,number(account,"pending_reclaim"));if(debt>0){consume(scope,member,debt,"DEBT_OFFSET",ref);db.jdbc().update("update hx_points set pending_reclaim=pending_reclaim-? where shop_id=? and member_id=?",debt,scope,member);change(scope,member,-debt,"DEBT_OFFSET",ref);}
    }
}
