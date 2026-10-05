package com.ruoyi.hexu;
import java.util.Map;
/** Approved provider implementations must verify signatures/decrypt callbacks before emitting these events. */
public interface PaymentGateway {
    /** True only when real credentials for this merchant are loaded and usable. */
    default boolean ready(String merchantNo){return false;}
    /** A withdrawal may freeze funds only when its exact payout channel is enabled. */
    default boolean payoutReady(String channel,String merchantNo){return false;}
    Map<String,Object> createPayment(Map<String,Object> payment,String openId);
    /** The refund id must be used as the provider idempotency key, including after uncertain timeouts. */
    Map<String,Object> submitRefund(Map<String,Object> refund,String merchantNo);
    Map<String,Object> submitPayout(Map<String,Object> withdrawal,String merchantNo);
    Map<String,Object> query(String operation,String id,String merchantNo);
    /** Adapter returns authenticated provider statements; manual uploads remain explicitly unverified. */
    default Map<String,Object> queryStatement(String merchantNo,String date){throw new BusinessException("渠道账单下载适配器尚未配置");}
    /** Must verify the provider certificate/signature and decrypt before returning any event. */
    default Map<String,Object> verifyNotification(String body,Map<String,String> headers){throw new BusinessException("支付回调验签适配器尚未配置");}
}
