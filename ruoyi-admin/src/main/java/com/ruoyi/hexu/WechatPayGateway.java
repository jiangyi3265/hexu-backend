package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.billdownload.BillDownloadServiceExtension;
import com.wechat.pay.java.service.billdownload.DigestBillEntity;
import com.wechat.pay.java.service.billdownload.model.BillType;
import com.wechat.pay.java.service.billdownload.model.GetTradeBillRequest;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** WeChat Pay API v3 adapter. Provider responses are verified by the official SDK. */
@Component
@ConditionalOnProperty(name = "hexu.wechat-pay.enabled", havingValue = "true")
public class WechatPayGateway implements PaymentGateway {
  private final HexuStore db;
  private final ObjectMapper json;

  @Value("${hexu.wechat.app-id:}")
  private String appId;

  @Value("${hexu.wechat-pay.notify-url:}")
  private String notifyUrl;

  @Value("${hexu.wechat-pay.all-shops-direct:false}")
  private boolean allShopsDirect;

  @Value("${hexu.wechat-pay.direct.merchant-id:}")
  private String directId;

  @Value("${hexu.wechat-pay.direct.merchant-serial:}")
  private String directSerial;

  @Value("${hexu.wechat-pay.direct.private-key-path:}")
  private String directKeyPath;

  @Value("${hexu.wechat-pay.direct.public-key-path:}")
  private String directPublicPath;

  @Value("${hexu.wechat-pay.direct.public-key-id:}")
  private String directPublicId;

  @Value("${hexu.wechat-pay.direct.api-v3-key:}")
  private String directV3;

  @Value("${hexu.wechat-pay.partner.merchant-id:}")
  private String partnerId;

  @Value("${hexu.wechat-pay.partner.merchant-serial:}")
  private String partnerSerial;

  @Value("${hexu.wechat-pay.partner.private-key-path:}")
  private String partnerKeyPath;

  @Value("${hexu.wechat-pay.partner.public-key-path:}")
  private String partnerPublicPath;

  @Value("${hexu.wechat-pay.partner.public-key-id:}")
  private String partnerPublicId;

  @Value("${hexu.wechat-pay.partner.api-v3-key:}")
  private String partnerV3;

  @Value("${hexu.wechat-pay.transfer.scene-id:}")
  private String transferSceneId;

  @Value("${hexu.wechat-pay.transfer.scene-report-infos:}")
  private String transferSceneReportInfos;

  private volatile Config directConfig, partnerConfig;

  public WechatPayGateway(HexuStore db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  private boolean directConfigured() {
    return appId.matches("wx[0-9a-fA-F]{16}")
        && directId.matches("[0-9]{8,20}")
        && !directSerial.isEmpty()
        && !directKeyPath.isEmpty()
        && !directPublicPath.isEmpty()
        && !directPublicId.isEmpty()
        && directV3.length() == 32
        && notifyUrl.startsWith("https://");
  }

  private boolean partnerConfigured() {
    return appId.matches("wx[0-9a-fA-F]{16}")
        && partnerId.matches("[0-9]{8,20}")
        && !partnerSerial.isEmpty()
        && !partnerKeyPath.isEmpty()
        && !partnerPublicPath.isEmpty()
        && !partnerPublicId.isEmpty()
        && partnerV3.length() == 32
        && notifyUrl.startsWith("https://");
  }

  private synchronized Config config(boolean partner) {
    require(partner ? partnerConfigured() : directConfigured(), "微信支付商户凭据、平台公钥或 HTTPS 回调地址未配置");
    if (partner && partnerConfig != null) return partnerConfig;
    if (!partner && directConfig != null) return directConfig;
    Config value =
        new RSAPublicKeyConfig.Builder()
            .merchantId(partner ? partnerId : directId)
            .merchantSerialNumber(partner ? partnerSerial : directSerial)
            .privateKeyFromPath(partner ? partnerKeyPath : directKeyPath)
            .publicKeyFromPath(partner ? partnerPublicPath : directPublicPath)
            .publicKeyId(partner ? partnerPublicId : directPublicId)
            .apiV3Key(partner ? partnerV3 : directV3)
            .build();
    if (partner) partnerConfig = value;
    else directConfig = value;
    return value;
  }

  @Override
  public boolean ready(String merchantNo) {
    return directId.equals(merchantNo)
        ? directConfigured()
        : partnerConfigured() && merchantNo.matches("[0-9]{8,20}");
  }

  @Override
  public boolean payoutReady(String channel, String merchantNo) {
    if (!"WECHAT".equals(channel) || merchantNo == null || merchantNo.isEmpty()) return false;
    boolean isPartner = !directId.equals(merchantNo);
    if (isPartner) {
      if (!partnerConfigured()) return false;
      Map<String, Object> shop = db.optional("select id from hx_shop where merchant_no=?", merchantNo);
      Map<String, Object> onboarding = shop == null ? null : db.optional("select transfer_auth_status from hx_merchant_onboarding where shop_id=?", shop.get("id"));
      if (onboarding == null || !"AUTHORIZED".equals(str(onboarding, "transfer_auth_status"))) return false;
    } else if (!directConfigured()) {
      return false;
    }
    try {
      transferSceneReportInfos();
      return transferSceneId != null && transferSceneId.matches("[A-Za-z0-9_-]{1,36}");
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private Map<String, Object> orderForPayment(String id) {
    return db.one(
        "select p.*,o.shop_id,o.status order_status,o.address_json,o.expires_at,s.kind from"
            + " hx_payment p join hx_order o on o.id=p.order_id join hx_shop s on s.id=o.shop_id"
            + " where p.id=?",
        id);
  }

  boolean partner(Map<String, Object> payment) {
    return "DEALER".equals(str(payment, "kind"))
        && (directId.isEmpty() || !directId.equals(str(payment, "merchant_no")));
  }

  private String outTradeNo(String paymentId) {
    require(paymentId.matches("PAY[0-9a-fA-F]{32}"), "支付单号不符合渠道长度限制");
    return paymentId.substring(3);
  }

  private String paymentId(String outTradeNo) {
    require(outTradeNo.matches("[0-9a-fA-F]{32}"), "渠道支付单号格式无效");
    return "PAY" + outTradeNo;
  }

  private void requirePaymentReady(Map<String, Object> payment) {
    boolean partner = partner(payment);
    String merchant = str(payment, "merchant_no");
    require(
        "UNPAID".equals(str(payment, "order_status"))
            && ((java.sql.Timestamp) payment.get("expires_at")).toInstant().isAfter(Instant.now()),
        "订单不可支付或已超时");
    Map<String, Object> shop =
        db.one("select merchant_no,payment_ready,status from hx_shop where id=?", payment.get("shop_id"));
    boolean sharedDirect = allShopsDirect && !partner;
    require(
        (sharedDirect
                ? "ACTIVE".equals(str(shop, "status"))
                : bool(shop, "payment_ready") && merchant.equals(str(shop, "merchant_no")))
            && (partner ? partnerConfigured() : directConfigured()),
        "商户收款尚未就绪");
    if (partner) {
      Map<String, Object> onboarding =
          db.one("select * from hx_merchant_onboarding where shop_id=?", payment.get("shop_id"));
      require(
          "VERIFIED".equals(str(onboarding, "application_status"))
              && "VERIFIED".equals(str(onboarding, "authorization_status"))
              && "VERIFIED".equals(str(onboarding, "certificate_status"))
              && appId.equals(str(onboarding, "app_id"))
              && merchant.equals(str(onboarding, "merchant_no"))
              && onboarding.get("expires_at") != null
              && ((java.sql.Date) onboarding.get("expires_at"))
                  .toLocalDate()
                  .isAfter(LocalDate.now()),
          "经销商进件、授权或证书已失效");
    } else require(directId.equals(merchant), "公司商户号与渠道配置不一致");
  }

  @Override
  public Map<String, Object> createPayment(Map<String, Object> payment, String openId) {
    require(openId != null && !openId.isEmpty(), "缺少已验证的微信 OpenID");
    Map<String, Object> row = orderForPayment(str(payment, "id"));
    requirePaymentReady(row);
    long amount = number(row, "amount");
    require(amount > 0 && amount <= Integer.MAX_VALUE, "支付金额超出微信渠道范围");
    String outNo = outTradeNo(str(row, "id"));
    if (partner(row)) {
      com.wechat.pay.java.service.partnerpayments.jsapi.model.PrepayRequest req =
          new com.wechat.pay.java.service.partnerpayments.jsapi.model.PrepayRequest();
      req.setSpAppid(appId);
      req.setSpMchid(partnerId);
      req.setSubMchid(str(row, "merchant_no"));
      req.setOutTradeNo(outNo);
      req.setDescription("商城订单 " + str(row, "order_id"));
      req.setNotifyUrl(notifyUrl);
      com.wechat.pay.java.service.partnerpayments.jsapi.model.Amount money =
          new com.wechat.pay.java.service.partnerpayments.jsapi.model.Amount();
      money.setTotal(Math.toIntExact(amount));
      money.setCurrency("CNY");
      req.setAmount(money);
      com.wechat.pay.java.service.partnerpayments.jsapi.model.Payer payer =
          new com.wechat.pay.java.service.partnerpayments.jsapi.model.Payer();
      payer.setSpOpenid(openId);
      req.setPayer(payer);
      com.wechat.pay.java.service.partnerpayments.jsapi.model.PrepayWithRequestPaymentResponse
          response =
              new com.wechat.pay.java.service.partnerpayments.jsapi.JsapiServiceExtension.Builder()
                  .config(config(true))
                  .build()
                  .prepayWithRequestPayment(req, appId);
      return map(
          "paymentId",
          row.get("id"),
          "appId",
          response.getAppId(),
          "timeStamp",
          response.getTimeStamp(),
          "nonceStr",
          response.getNonceStr(),
          "package",
          response.getPackageVal(),
          "signType",
          response.getSignType(),
          "paySign",
          response.getPaySign(),
          "paymentMode",
          "PARTNER");
    }
    com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest req =
        new com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest();
    req.setAppid(appId);
    req.setMchid(directId);
    req.setOutTradeNo(outNo);
    req.setDescription("商城订单 " + str(row, "order_id"));
    req.setNotifyUrl(notifyUrl);
    com.wechat.pay.java.service.payments.jsapi.model.Amount money =
        new com.wechat.pay.java.service.payments.jsapi.model.Amount();
    money.setTotal(Math.toIntExact(amount));
    money.setCurrency("CNY");
    req.setAmount(money);
    com.wechat.pay.java.service.payments.jsapi.model.Payer payer =
        new com.wechat.pay.java.service.payments.jsapi.model.Payer();
    payer.setOpenid(openId);
    req.setPayer(payer);
    com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse response =
        new com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension.Builder()
            .config(config(false))
            .build()
            .prepayWithRequestPayment(req);
    return map(
        "paymentId",
        row.get("id"),
        "appId",
        response.getAppId(),
        "timeStamp",
        response.getTimeStamp(),
        "nonceStr",
        response.getNonceStr(),
        "package",
        response.getPackageVal(),
        "signType",
        response.getSignType(),
        "paySign",
        response.getPaySign(),
        "paymentMode",
        "DIRECT");
  }

  @Override
  public Map<String, Object> submitRefund(Map<String, Object> refund, String merchantNo) {
    Map<String, Object> row =
        db.one(
            "select r.*,o.merchant_no,o.shop_id,p.id payment_id,p.amount original_amount,s.kind"
                + " from hx_refund r join hx_order o on o.id=r.order_id join hx_payment p on"
                + " p.order_id=o.id join hx_shop s on s.id=o.shop_id where r.id=?",
            refund.get("id"));
    require(
        merchantNo.equals(str(row, "merchant_no")) && "APPROVED".equals(str(row, "status")),
        "退款商户快照或审核状态不一致");
    boolean partner = partner(row);
    require(partner ? partnerConfigured() : directConfigured(), "原交易渠道凭据未配置");
    if (!partner) require(directId.equals(merchantNo), "直连退款商户号不匹配");
    CreateRequest req = new CreateRequest();
    req.setOutTradeNo(outTradeNo(str(row, "payment_id")));
    req.setOutRefundNo(str(row, "id"));
    req.setNotifyUrl(notifyUrl);
    if (partner) req.setSubMchid(merchantNo);
    AmountReq money = new AmountReq();
    money.setRefund(number(row, "amount"));
    money.setTotal(number(row, "original_amount"));
    money.setCurrency("CNY");
    req.setAmount(money);
    com.wechat.pay.java.service.refund.model.Refund response =
        new RefundService.Builder().config(config(partner)).build().create(req);
    require(str(row, "id").equals(response.getOutRefundNo()), "微信退款应答单号不一致");
    return map(
        "refundId",
        row.get("id"),
        "channelRef",
        response.getRefundId(),
        "status",
        String.valueOf(response.getStatus()),
        "paymentMode",
        partner ? "PARTNER" : "DIRECT");
  }

  @Override
  public Map<String, Object> submitPayout(Map<String, Object> withdrawal, String merchantNo) {
    require("WECHAT".equals(str(withdrawal, "channel")), "当前提现不是微信零钱渠道");
    require(payoutReady("WECHAT", merchantNo), "微信商家转账渠道尚未完成商户授权或场景配置");
    boolean isPartner = !directId.equals(merchantNo);
    Map<String, Object> identity = db.optional(
        "select open_id,app_id from hx_wechat_account where member_id=? and app_id=? order by app_id limit 1",
        withdrawal.get("member_id"), appId);
    require(identity != null && !str(identity, "open_id").isEmpty(), "提现用户尚未完成当前小程序微信授权");
    String outBillNo = ensureOutBillNo(withdrawal);
    String existingBill = str(withdrawal, "provider_bill_no");
    if (!existingBill.isEmpty()) {
      return map(
          "status", str(withdrawal, "provider_state"),
          "mchId", isPartner ? partnerId : directId,
          "subMchId", isPartner ? merchantNo : "",
          "appId", appId,
          "package", str(withdrawal, "provider_package"),
          "outBillNo", outBillNo,
          "transferBillNo", existingBill);
    }
    Map<String, Object> member = db.one("select name from hx_member where id=?", withdrawal.get("member_id"));
    String name = str(member, "name");
    if ("微信用户".equals(name)) name = "";
    WechatTransferClient client = new WechatTransferClient(
        config(isPartner), json, appId, notifyUrl,
        transferSceneId, isPartner ? merchantNo : null, isPartner);
    Map<String, Object> result = client.create(
        outBillNo,
        str(identity, "open_id"),
        number(withdrawal, "net"),
        name,
        "分销收益提现",
        transferSceneReportInfos());
    String state = str(result, "package_info").isEmpty() ? "ACCEPTED" : "WAIT_USER_CONFIRM";
    String transferBillNo = str(result, "transfer_bill_no");
    db.jdbc().update(
        "update hx_withdrawal set status='PROCESSING',provider_out_bill_no=?,provider_bill_no=?,provider_state=?,provider_package=?,provider_updated_at=CURRENT_TIMESTAMP where id=? and status='APPROVED'",
        outBillNo, transferBillNo, state, str(result, "package_info"), withdrawal.get("id"));
    return map(
        "status", state,
        "mchId", isPartner ? partnerId : directId,
        "subMchId", isPartner ? merchantNo : "",
        "appId", appId,
        "package", str(result, "package_info"),
        "outBillNo", outBillNo,
        "transferBillNo", transferBillNo);
  }

  @Override
  public Map<String, Object> query(String operation, String id, String merchantNo) {
    if ("payment".equals(operation)) {
      Map<String, Object> row = orderForPayment(id);
      require(merchantNo.equals(str(row, "merchant_no")), "支付商户号不一致");
      boolean partner = partner(row);
      String outNo = outTradeNo(id);
      if (partner) {
        com.wechat.pay.java.service.partnerpayments.jsapi.model.QueryOrderByOutTradeNoRequest req =
            new com.wechat.pay.java.service.partnerpayments.jsapi.model
                .QueryOrderByOutTradeNoRequest();
        req.setOutTradeNo(outNo);
        req.setSpMchid(partnerId);
        req.setSubMchid(merchantNo);
        com.wechat.pay.java.service.partnerpayments.jsapi.model.Transaction result =
            new com.wechat.pay.java.service.partnerpayments.jsapi.JsapiServiceExtension.Builder()
                .config(config(true))
                .build()
                .queryOrderByOutTradeNo(req);
        require(
            outNo.equals(result.getOutTradeNo())
                && merchantNo.equals(result.getSubMchid())
                && result.getAmount() != null
                && result.getAmount().getTotal() != null
                && result.getAmount().getTotal() == number(row, "amount"),
            "微信查单商户或金额不一致");
        return map(
            "status",
            String.valueOf(result.getTradeState()),
            "channelRef",
            result.getTransactionId(),
            "merchantNo",
            merchantNo,
            "amount",
            row.get("amount"),
            "verified",
            true);
      }
      require(directId.equals(merchantNo), "直连支付商户号不一致");
      com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest req =
          new com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest();
      req.setOutTradeNo(outNo);
      req.setMchid(directId);
      com.wechat.pay.java.service.payments.model.Transaction result =
          new com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension.Builder()
              .config(config(false))
              .build()
              .queryOrderByOutTradeNo(req);
      require(
          outNo.equals(result.getOutTradeNo())
              && directId.equals(result.getMchid())
              && result.getAmount() != null
              && result.getAmount().getTotal() != null
              && result.getAmount().getTotal() == number(row, "amount"),
          "微信查单商户或金额不一致");
      return map(
          "status",
          String.valueOf(result.getTradeState()),
          "channelRef",
          result.getTransactionId(),
          "merchantNo",
          merchantNo,
          "amount",
          row.get("amount"),
          "verified",
          true);
    }
    if ("refund".equals(operation)) {
      Map<String, Object> row =
          db.one(
              "select r.*,o.merchant_no,s.kind from hx_refund r join hx_order o on o.id=r.order_id"
                  + " join hx_shop s on s.id=o.shop_id where r.id=?",
              id);
      require(merchantNo.equals(str(row, "merchant_no")), "退款商户号不一致");
      boolean partner = partner(row);
      QueryByOutRefundNoRequest req = new QueryByOutRefundNoRequest();
      req.setOutRefundNo(id);
      if (partner) req.setSubMchid(merchantNo);
      com.wechat.pay.java.service.refund.model.Refund result =
          new RefundService.Builder().config(config(partner)).build().queryByOutRefundNo(req);
      require(
          id.equals(result.getOutRefundNo())
              && result.getAmount() != null
              && result.getAmount().getRefund() != null
              && result.getAmount().getRefund() == number(row, "amount"),
          "微信退款查单金额不一致");
      return map(
          "status",
          String.valueOf(result.getStatus()),
          "channelRef",
          result.getRefundId(),
          "merchantNo",
          merchantNo,
          "amount",
          row.get("amount"),
          "verified",
          true);
    }
    if ("payout".equals(operation)) {
      Map<String, Object> row = db.one("select w.*,s.kind from hx_withdrawal w join hx_shop s on s.id=w.shop_id where w.id=?", id);
      require(merchantNo.equals(str(row, "merchant_no")), "提现商户号不一致");
      String outBillNo = str(row, "provider_out_bill_no");
      require(!outBillNo.isEmpty(), "提现尚未提交微信渠道");
      boolean isPartner = !directId.equals(merchantNo);
      require(payoutReady("WECHAT", merchantNo), "微信商家转账渠道尚未完成商户授权或场景配置");
      Map<String, Object> result = new WechatTransferClient(
          config(isPartner), json, appId, notifyUrl,
          transferSceneId, isPartner ? merchantNo : null, isPartner).query(outBillNo);
      String state = str(result, "state");
      require(Arrays.asList("ACCEPTED", "PROCESSING", "WAIT_USER_CONFIRM", "TRANSFERING", "SUCCESS", "FAIL", "CANCELING", "CANCELLED").contains(state), "微信转账状态无效");
      require(outBillNo.equals(str(result, "out_bill_no")), "微信转账单号不匹配");
      String providerMerchant = isPartner ? str(result, "sub_mchid") : str(result, "mchid");
      require(merchantNo.equals(providerMerchant) && appId.equals(str(result, "appid")) && number(result, "transfer_amount") == number(row, "net"), "微信转账商户、AppID或金额不匹配");
      return map("status", state, "channelRef", str(result, "transfer_bill_no"), "merchantNo", merchantNo, "amount", number(result, "transfer_amount"), "verified", true, "outBillNo", outBillNo, "packageInfo", str(row, "provider_package"));
    }
    throw new BusinessException("该渠道查询类型尚未配置");
  }

  private String ensureOutBillNo(Map<String, Object> withdrawal) {
    String current = str(withdrawal, "provider_out_bill_no");
    if (!current.isEmpty()) return current;
    String id = str(withdrawal, "id").replaceAll("[^A-Za-z0-9]", "");
    String generated = id.length() <= 32 ? id : id.substring(id.length() - 32);
    require(generated.matches("[A-Za-z0-9]{1,32}"), "无法生成微信提现渠道单号");
    db.jdbc().update("update hx_withdrawal set provider_out_bill_no=? where id=? and (provider_out_bill_no is null or provider_out_bill_no='')", generated, withdrawal.get("id"));
    return generated;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, String>> transferSceneReportInfos() {
    require(transferSceneReportInfos != null && !transferSceneReportInfos.trim().isEmpty(), "微信转账场景报备信息未配置");
    try {
      List<Map<String, String>> result = json.readValue(transferSceneReportInfos, List.class);
      require(result != null && !result.isEmpty(), "微信转账场景报备信息不能为空");
      return result;
    } catch (Exception e) {
      throw new BusinessException("微信转账场景报备信息配置格式无效");
    }
  }

  @Override
  public Map<String, Object> queryStatement(String merchantNo, String date) {
    boolean partner = !directId.equals(merchantNo);
    require(ready(merchantNo), "账单商户渠道尚未配置");
    GetTradeBillRequest req = new GetTradeBillRequest();
    req.setBillDate(LocalDate.parse(date).toString());
    req.setBillType(BillType.ALL);
    if (partner) req.setSubMchid(merchantNo);
    DigestBillEntity bill =
        new BillDownloadServiceExtension.Builder()
            .config(config(partner))
            .build()
            .getTradeBill(req);
    try (InputStream in = bill.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] chunk = new byte[8192];
      int n;
      while ((n = in.read(chunk)) != -1) {
        require(bytes.size() + n <= 10 * 1024 * 1024, "渠道账单超过10MB，请使用离线下载");
        bytes.write(chunk, 0, n);
      }
      require(bill.verifyHash(), "渠道账单摘要校验失败");
      return map(
          "merchantNo",
          merchantNo,
          "date",
          date,
          "verified",
          true,
          "content",
          new String(bytes.toByteArray(), "UTF-8"));
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException("渠道账单读取失败");
    }
  }

  private String header(Map<String, String> headers, String name) {
    for (Map.Entry<String, String> e : headers.entrySet())
      if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
    return "";
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> nested(Map<String, Object> row, String key) {
    return row.get(key) instanceof Map
        ? (Map<String, Object>) row.get(key)
        : Collections.emptyMap();
  }

  @SuppressWarnings("unchecked")
  @Override
  public Map<String, Object> verifyNotification(String body, Map<String, String> headers) {
    String timestamp = header(headers, "Wechatpay-Timestamp"),
        signature = header(headers, "Wechatpay-Signature"),
        serial = header(headers, "Wechatpay-Serial"),
        nonce = header(headers, "Wechatpay-Nonce");
    require(
        !signature.startsWith("WECHATPAY/SIGNTEST/")
            && !signature.isEmpty()
            && !serial.isEmpty()
            && !nonce.isEmpty(),
        "微信支付回调签名缺失或为探测签名");
    long seconds;
    try {
      seconds = Long.parseLong(timestamp);
    } catch (Exception e) {
      throw new BusinessException("微信支付回调时间戳无效");
    }
    long now = Instant.now().getEpochSecond();
    require(seconds > 0 && seconds >= now - 300 && seconds <= now + 300, "微信支付回调已超出五分钟验签窗口");
    RequestParam request =
        new RequestParam.Builder()
            .serialNumber(serial)
            .timestamp(timestamp)
            .nonce(nonce)
            .signature(signature)
            .body(body)
            .build();
    Map<String, Object> decrypted = null;
    boolean partner = false;
    for (boolean mode : new boolean[] {false, true}) {
      if (!(mode ? partnerConfigured() : directConfigured())) continue;
      try {
        decrypted =
            new NotificationParser(
                    (com.wechat.pay.java.core.notification.NotificationConfig) config(mode))
                .parse(request, Map.class);
        partner = mode;
        break;
      } catch (RuntimeException ignored) {
        /* The other merchant may own this encrypted callback. */
      }
    }
    require(decrypted != null, "微信支付回调验签或解密失败");
    Map<String, Object> raw;
    try {
      raw = json.readValue(body, Map.class);
    } catch (Exception e) {
      throw new BusinessException("微信支付回调报文无效");
    }
    String event = str(raw, "event_type"),
        merchant = partner ? str(decrypted, "sub_mchid") : str(decrypted, "mchid");
    require(
        !merchant.isEmpty()
            && ("MCHTRANSFER.BILL.FINISHED".equals(event)
                ? (partner ? partnerId.equals(str(decrypted, "mchid")) : directId.equals(merchant))
                : (partner ? partnerId.equals(str(decrypted, "sp_mchid")) : directId.equals(merchant))),
        "微信支付回调商户号不匹配");
    if ("TRANSACTION.SUCCESS".equals(event)) {
      String id = paymentId(str(decrypted, "out_trade_no"));
      Map<String, Object> payment = orderForPayment(id);
      Map<String, Object> money = nested(decrypted, "amount");
      require(
          partner(payment) == partner
              && merchant.equals(str(payment, "merchant_no"))
              && appId.equals(str(decrypted, partner ? "sp_appid" : "appid"))
              && number(money, "total") == number(payment, "amount")
              && "SUCCESS".equals(str(decrypted, "trade_state"))
              && !str(decrypted, "transaction_id").isEmpty(),
          "微信付款回调订单、金额或渠道流水不匹配");
      return map(
          "type",
          "PAYMENT_SUCCESS",
          "id",
          id,
          "merchantNo",
          merchant,
          "amount",
          number(money, "total"),
          "channelRef",
          str(decrypted, "transaction_id"));
    }
    if ("REFUND.SUCCESS".equals(event)) {
      String id = str(decrypted, "out_refund_no");
      Map<String, Object> refund =
          db.one(
              "select r.*,o.merchant_no,p.id payment_id,s.kind from hx_refund r join hx_order o on"
                  + " o.id=r.order_id join hx_payment p on p.order_id=o.id join hx_shop s on"
                  + " s.id=o.shop_id where r.id=?",
              id);
      Map<String, Object> money = nested(decrypted, "amount");
      require(
          partner(refund) == partner
              && merchant.equals(str(refund, "merchant_no"))
              && outTradeNo(str(refund, "payment_id")).equals(str(decrypted, "out_trade_no"))
              && number(money, "refund") == number(refund, "amount")
              && "SUCCESS".equals(str(decrypted, "refund_status"))
              && !str(decrypted, "refund_id").isEmpty(),
          "微信退款回调原交易、商户、金额或流水不匹配");
      return map(
          "type",
          "REFUND_SUCCESS",
          "id",
          id,
          "merchantNo",
          merchant,
          "amount",
          number(money, "refund"),
          "channelRef",
          str(decrypted, "refund_id"));
    }
    if ("MCHTRANSFER.BILL.FINISHED".equals(event)) {
      String state = str(decrypted, "state");
      require(Arrays.asList("SUCCESS", "FAIL", "CANCELLED").contains(state), "微信转账回调不是终态");
      String outBillNo = str(decrypted, "out_bill_no");
      Map<String, Object> withdrawal = db.one("select w.*,s.kind from hx_withdrawal w join hx_shop s on s.id=w.shop_id where w.provider_out_bill_no=?", outBillNo);
      String providerMerchant = partner ? str(decrypted, "sub_mchid") : str(decrypted, "mchid");
      String expectedServiceMerchant = partner ? partnerId : directId;
      require(
          (partner ? expectedServiceMerchant.equals(str(decrypted, "mchid")) : expectedServiceMerchant.equals(providerMerchant))
              && providerMerchant.equals(str(withdrawal, "merchant_no"))
              && (str(decrypted, "appid").isEmpty() || appId.equals(str(decrypted, "appid")))
              && number(decrypted, "transfer_amount") == number(withdrawal, "net")
              && !str(decrypted, "transfer_bill_no").isEmpty(),
          "微信转账回调商户、AppID、金额或流水不匹配");
      Map<String, Object> identity = db.optional("select open_id from hx_wechat_account where member_id=? and app_id=?", withdrawal.get("member_id"), appId);
      require(identity != null && str(identity, "open_id").equals(str(decrypted, "openid")), "微信转账回调收款用户不匹配");
      db.jdbc().update("update hx_withdrawal set provider_bill_no=?,provider_state=?,provider_updated_at=CURRENT_TIMESTAMP where id=?", str(decrypted, "transfer_bill_no"), state, withdrawal.get("id"));
      return map(
          "type", "SUCCESS".equals(state) ? "PAYOUT_SUCCESS" : "PAYOUT_FAILED",
          "id", withdrawal.get("id"),
          "merchantNo", providerMerchant,
          "amount", number(decrypted, "transfer_amount"),
          "channelRef", str(decrypted, "transfer_bill_no"),
          "reason", str(decrypted, "fail_reason"));
    }
    throw new BusinessException("暂不支持该微信支付回调事件");
  }
}
