package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HexuMerchantOnboarding {
  private final HexuStore db;
  private final PaymentGateway gateway;

  @Value("${hexu.wechat.app-id:}")
  private String companyAppId;

  @Value("${hexu.wechat-pay.direct.merchant-id:}")
  private String companyMerchantNo;

  @Value("${hexu.wechat-pay.all-shops-direct:false}")
  private boolean allShopsDirect;

  @Value("${hexu.wechat-pay.enabled:false}")
  private boolean realPaymentEnabled;

  @Value("${hexu.sandbox.enabled:false}") private boolean sandboxEnabled;
  @Autowired(required = false) private Environment environment;

  private boolean sandboxReady() {
    return sandboxEnabled && environment != null
        && environment.acceptsProfiles(Profiles.of("hexu-dev"));
  }

  public HexuMerchantOnboarding(HexuStore db, PaymentGateway gateway) {
    this.db = db;
    this.gateway = gateway;
  }

  public Map<String, Object> status(Actor actor, long shop) {
    db.manage(actor, shop, "OWNER", "FINANCE");
    Map<String, Object> row =
        db.optional("select * from hx_merchant_onboarding where shop_id=?", shop);
    Map<String, Object> s =
        db.one("select kind,owner_id,merchant_no,payment_ready,status from hx_shop where id=?", shop);
    boolean canManage = actor.platform || !actor.staff && actor.id == number(s, "owner_id");
    if (actor.staff && !actor.platform) {
      Map<String, Object> staff =
          db.optional(
              "select role_code from hx_staff where user_id=? and shop_id=?", actor.id, shop);
      canManage = staff != null && "OWNER".equals(str(staff, "role_code"));
    }
    if (realPaymentEnabled && allShopsDirect)
      return map(
          "shopId", shop,
          "paymentMode", "DIRECT",
          "sharedDirect", true,
          "merchantNo", companyMerchantNo,
          "appId", companyAppId,
          "paymentReady", "ACTIVE".equals(str(s, "status")) && gateway.ready(companyMerchantNo),
          "canManage", false,
          "canVerify", false);
    if (row == null)
      return map(
          "shopId",
          shop,
          "paymentMode",
          "DEALER".equals(str(s, "kind")) ? "PARTNER" : "DIRECT",
          "applicationStatus",
          "NOT_SUBMITTED",
           "authorizationStatus",
           "NOT_SUBMITTED",
           "transferAuthRef",
           null,
           "transferAuthStatus",
           "NOT_SUBMITTED",
          "certificateStatus",
          "NOT_SUBMITTED",
          "merchantNo",
          "DEALER".equals(str(s, "kind")) ? null : s.get("merchant_no"),
          "canVerify",
          actor.platform,
          "canManage",
          canManage,
          "sandboxReady",
          sandboxReady(),
          "paymentReady",
          !"DEALER".equals(str(s, "kind"))
              && str(s, "merchant_no").equals(companyMerchantNo)
              && bool(s, "payment_ready")
              && gateway.ready(str(s, "merchant_no")));
    boolean active =
        verified(row) && gateway.ready(str(row, "merchant_no")) && bool(s, "payment_ready");
    boolean expired =
        row.get("expires_at") != null
            && !((java.sql.Date) row.get("expires_at")).toLocalDate().isAfter(LocalDate.now());
    Map<String, Object> result =
        map(
            "shopId",
            shop,
            "paymentMode",
            "PARTNER",
            "canVerify",
            actor.platform,
            "canManage",
            canManage,
            "sandboxReady",
            sandboxReady(),
            "subjectType",
            row.get("subject_type"),
            "subjectName",
            row.get("subject_name"),
            "legalRepresentative",
            row.get("legal_representative"),
            "licenseNo",
            row.get("license_no"),
            "contactPhone",
            row.get("contact_phone"),
            "settlementBank",
            row.get("settlement_bank"),
            "settlementAccountRef",
            row.get("settlement_account_ref"),
            "attachmentIds",
            row.get("attachment_ids") == null
                ? Collections.emptyList()
                : db.parse(row.get("attachment_ids")).get("items"),
            "applicationRef",
            row.get("application_ref"),
            "applicationStatus",
            row.get("application_status"),
            "authorizationRef",
            row.get("authorization_ref"),
            "authorizationStatus",
            row.get("authorization_status"),
            "transferAuthRef",
            row.get("transfer_auth_ref"),
            "transferAuthStatus",
            row.get("transfer_auth_status"),
            "transferAuthAt",
            row.get("transfer_auth_at"),
            "appId",
            row.get("app_id"),
            "merchantNo",
            row.get("merchant_no"),
            "certificateRef",
            row.get("certificate_ref"),
            "certificateStatus",
            row.get("certificate_status"),
            "certificateSerial",
            row.get("certificate_serial"),
            "expiresAt",
            row.get("expires_at") == null ? null : row.get("expires_at").toString(),
            "contractedFeeBps",
            row.get("contracted_fee_bps"),
            "settlementDays",
            row.get("settlement_days"),
            "abnormalReason",
            expired ? "证书已过期" : row.get("abnormal_reason"),
            "rejectionReason",
            row.get("rejection_reason"),
            "paymentReady",
            active);
    return result;
  }

  private boolean verified(Map<String, Object> row) {
    return "VERIFIED".equals(str(row, "application_status"))
        && "VERIFIED".equals(str(row, "authorization_status"))
        && "VERIFIED".equals(str(row, "certificate_status"))
        && row.get("expires_at") != null
        && ((java.sql.Date) row.get("expires_at")).toLocalDate().isAfter(LocalDate.now());
  }

  public String receivingMerchant(Map<String, Object> shop) {
    return allShopsDirect ? companyMerchantNo : str(shop, "merchant_no");
  }

  public void requireReady(long shop, String merchantNo) {
    if (allShopsDirect) {
      db.shop(shop);
      require(merchantNo.equals(companyMerchantNo), "订单收款方式已变更，请重新下单");
      require(gateway.ready(merchantNo), "公司统一收款渠道尚未就绪");
      return;
    }
    Map<String, Object> s =
        db.one("select kind,merchant_no,payment_ready from hx_shop where id=?", shop);
    require(
        merchantNo.equals(str(s, "merchant_no"))
            && bool(s, "payment_ready")
            && gateway.ready(merchantNo),
        "商户收款配置尚未就绪");
    if ("DEALER".equals(str(s, "kind"))) {
      Map<String, Object> row =
          db.one("select * from hx_merchant_onboarding where shop_id=?", shop);
      require(merchantNo.equals(str(row, "merchant_no")) && verified(row), "经销商进件、授权或证书已失效");
    } else require(merchantNo.equals(companyMerchantNo), "公司直营或批发单必须使用公司直连商户号");
  }

  private void ensure(long shop) {
    Map<String, Object> source = db.one("select id,kind from hx_shop where id=? for update", shop);
    require("DEALER".equals(str(source, "kind")), "仅经销商商城需要独立商户进件");
    if (db.optional("select shop_id from hx_merchant_onboarding where shop_id=?", shop) == null)
      db.jdbc().update("insert into hx_merchant_onboarding(shop_id) values(?)", shop);
  }

  @Transactional
  public Map<String, Object> reference(Actor actor, long shop, Map<String, Object> body) {
    db.manage(actor, shop, "OWNER");
    ensure(shop);
    String stage = str(body, "stage"), ref = str(body, "reference").trim(), column, status;
    switch (stage) {
      case "APPLICATION":
        column = "application_ref";
        status = "application_status";
        break;
      case "AUTHORIZATION":
        column = "authorization_ref";
        status = "authorization_status";
        break;
      case "CERTIFICATE":
        column = "certificate_ref";
        status = "certificate_status";
        break;
      case "TRANSFER":
        column = "transfer_auth_ref";
        status = "transfer_auth_status";
        break;
      default:
        throw new BusinessException("凭证阶段无效");
    }
    require(ref.matches("[A-Za-z0-9_-]{4,100}"), "渠道凭证编号格式无效");
    Map<String, Object> row =
        db.one("select * from hx_merchant_onboarding where shop_id=? for update", shop);
    require("PENDING".equals(str(row, status)), "仅待核验阶段可补录渠道凭证");
    require(str(row, column).isEmpty() || str(row, column).equals(ref), "已有渠道凭证不可替换，请联系平台复核");
    db.jdbc()
        .update(
            "update hx_merchant_onboarding set "
                + column
                + "=?,updated_at=CURRENT_TIMESTAMP where shop_id=?",
            ref,
            shop);
    db.audit(
        actor,
        shop,
        "MERCHANT_REFERENCE",
        String.valueOf(shop),
        null,
        map("stage", stage, "reference", ref));
    return status(actor, shop);
  }

  @Transactional
  public Map<String, Object> submit(Actor actor, long shop, Map<String, Object> body) {
    db.manage(actor, shop, "OWNER");
    String subject = str(body, "subjectName").trim(),
        legal = str(body, "legalRepresentative").trim(),
        license = str(body, "licenseNo").trim(),
        phone = str(body, "contactPhone").trim();
    String subjectType = str(body, "subjectType");
    require(
        Arrays.asList("ENTERPRISE", "INDIVIDUAL").contains(subjectType)
            && subject.length() >= 2
            && subject.length() <= 160
            && legal.length() >= 2
            && legal.length() <= 80
            && license.matches("[0-9A-Z]{18}")
            && phone.matches("1[3-9][0-9]{9}"),
        "主体类型、商户主体、法人、统一社会信用代码或联系手机格式无效");
    require(
        str(body, "settlementBank").length() <= 120
            && str(body, "settlementAccountRef").length() <= 128
            && str(body, "applicationRef").length() <= 100,
        "结算资料或进件编号过长");
    List<?> attachments =
        body.get("attachmentIds") instanceof List
            ? (List<?>) body.get("attachmentIds")
            : Collections.emptyList();
    require(!attachments.isEmpty() && attachments.size() <= 12, "进件附件须为1至12份");
    for (Object id : attachments)
      require(
          db.optional(
                  "select id from hx_attachment where id=? and shop_id=? and purpose='MERCHANT'",
                  id,
                  shop)
              != null,
          "进件附件无效");
    ensure(shop);
    String maskedLegal = legal.substring(0, 1) + "*",
        maskedLicense = "**************" + license.substring(14),
        maskedPhone = "*******" + phone.substring(7);
    db.jdbc()
        .update(
            "update hx_merchant_onboarding set"
                + " subject_type=?,subject_name=?,legal_representative=?,license_no=?,contact_phone=?,settlement_bank=?,settlement_account_ref=?,attachment_ids=?,application_ref=?,application_status='PENDING',authorization_status='NOT_SUBMITTED',transfer_auth_ref=null,transfer_auth_status='NOT_SUBMITTED',transfer_auth_at=null,certificate_status='NOT_SUBMITTED',app_id=null,merchant_no=null,authorization_ref=null,certificate_ref=null,certificate_serial=null,expires_at=null,contracted_fee_bps=null,settlement_days=null,rejection_reason=null,updated_at=CURRENT_TIMESTAMP"
                + " where shop_id=?",
            subjectType,
            subject,
            maskedLegal,
            maskedLicense,
            maskedPhone,
            str(body, "settlementBank"),
            str(body, "settlementAccountRef"),
            db.json(map("items", attachments)),
            str(body, "applicationRef"),
            shop);
    db.jdbc().update("update hx_shop set payment_ready=false where id=?", shop);
    db.audit(
        actor,
        shop,
        "MERCHANT_SUBMIT",
        String.valueOf(shop),
        null,
        map("applicationRef", str(body, "applicationRef")));
    return status(actor, shop);
  }

  @Transactional
  public Map<String, Object> authorize(Actor actor, long shop, Map<String, Object> body) {
    db.manage(actor, shop, "OWNER");
    ensure(shop);
    String appId = str(body, "appId"),
        merchant = str(body, "merchantNo"),
        ref = str(body, "authorizationRef");
    require(
        appId.matches("wx[0-9a-fA-F]{16}")
            && appId.equals(companyAppId)
            && merchant.matches("[0-9]{8,20}")
            && !merchant.equals(companyMerchantNo)
            && ref.length() <= 100,
        "AppID须为已配置的公司小程序，且子商户号和授权编号有效");
    require(
        "VERIFIED"
            .equals(
                str(
                    db.one(
                        "select application_status from hx_merchant_onboarding where shop_id=?",
                        shop),
                    "application_status")),
        "请先完成商户进件核验");
    db.jdbc()
        .update(
            "update hx_merchant_onboarding set"
                + " app_id=?,merchant_no=?,authorization_ref=?,authorization_status='PENDING',transfer_auth_ref=null,transfer_auth_status='PENDING',transfer_auth_at=null,certificate_status='NOT_SUBMITTED',certificate_ref=null,certificate_serial=null,expires_at=null,contracted_fee_bps=null,settlement_days=null,rejection_reason=null,updated_at=CURRENT_TIMESTAMP"
                + " where shop_id=?",
            appId,
            merchant,
            ref,
            shop);
    db.jdbc().update("update hx_shop set payment_ready=false where id=?", shop);
    db.audit(
        actor,
        shop,
        "MERCHANT_AUTHORIZE",
        String.valueOf(shop),
        null,
        map("appId", appId, "merchantNo", merchant));
    return status(actor, shop);
  }

  @Transactional
  public Map<String, Object> certificate(Actor actor, long shop, Map<String, Object> body) {
    db.manage(actor, shop, "OWNER");
    ensure(shop);
    String serial = str(body, "serialNo"),
        ref = str(body, "certificateRef"),
        expiry = str(body, "expiresAt");
    LocalDate expires;
    try {
      expires = LocalDate.parse(expiry);
    } catch (Exception e) {
      throw new BusinessException("证书到期日须为YYYY-MM-DD");
    }
    require(
        serial.matches("[A-Za-z0-9]{8,100}")
            && ref.length() <= 100
            && expires.isAfter(LocalDate.now()),
        "证书编号、凭证或有效期无效");
    require(
        body.get("contractedFeeBps") != null && body.get("settlementDays") != null,
        "请填写渠道签约费率和结算周期");
    long fee = number(body, "contractedFeeBps"), days = number(body, "settlementDays");
    require(fee >= 0 && fee <= 10000 && days >= 0 && days <= 365, "签约费率或结算周期无效");
    require(
        "VERIFIED"
            .equals(
                str(
                    db.one(
                        "select authorization_status from hx_merchant_onboarding where shop_id=?",
                        shop),
                    "authorization_status")),
        "请先完成AppID授权核验");
    db.jdbc()
        .update(
            "update hx_merchant_onboarding set"
                + " certificate_serial=?,certificate_ref=?,expires_at=?,contracted_fee_bps=?,settlement_days=?,certificate_status='PENDING',rejection_reason=null,updated_at=CURRENT_TIMESTAMP"
                + " where shop_id=?",
            serial,
            ref,
            java.sql.Date.valueOf(expires),
            fee,
            days,
            shop);
    db.jdbc().update("update hx_shop set payment_ready=false where id=?", shop);
    db.audit(
        actor,
        shop,
        "MERCHANT_CERTIFICATE",
        String.valueOf(shop),
        null,
        map(
            "serialNo",
            serial,
            "expiresAt",
            expiry,
            "contractedFeeBps",
            fee,
            "settlementDays",
            days));
    return status(actor, shop);
  }

  @Transactional
  public Map<String, Object> verify(Actor actor, long shop, Map<String, Object> body) {
    db.platform(actor);
    ensure(shop);
    String stage = str(body, "stage"), column;
    switch (stage) {
      case "APPLICATION":
        column = "application_status";
        break;
      case "AUTHORIZATION":
        column = "authorization_status";
        break;
      case "CERTIFICATE":
        column = "certificate_status";
        break;
      case "TRANSFER":
        column = "transfer_auth_status";
        break;
      default:
        throw new BusinessException("核验阶段无效");
    }
    Map<String, Object> row =
        db.one("select * from hx_merchant_onboarding where shop_id=? for update", shop);
    require("PENDING".equals(str(row, column)), "当前阶段不是待核验状态");
    boolean approve = bool(body, "approve");
    String reason = str(body, "reason").trim();
    require(approve || reason.length() >= 2, "驳回必须填写原因");
    if (approve) {
      require(!str(row, "application_ref").isEmpty(), "缺少渠道进件申请编号");
      if (!"APPLICATION".equals(stage))
        require(!str(row, "authorization_ref").isEmpty(), "缺少AppID授权凭证编号");
      if ("CERTIFICATE".equals(stage))
        require(!str(row, "certificate_ref").isEmpty(), "缺少证书核验凭证编号");
      if ("TRANSFER".equals(stage))
        require("VERIFIED".equals(str(row, "certificate_status")) && !str(row, "transfer_auth_ref").isEmpty(), "请先完成证书核验并补入商家转账授权凭证编号");
    }
    db.jdbc()
        .update(
            "update hx_merchant_onboarding set "
                + column
            + "=?,rejection_reason=?,transfer_auth_at=CASE WHEN ?='TRANSFER' AND ?='VERIFIED' THEN CURRENT_TIMESTAMP ELSE transfer_auth_at END,updated_at=CURRENT_TIMESTAMP where shop_id=?",
            approve ? "VERIFIED" : "REJECTED",
            approve ? null : reason,
            stage,
            approve ? "VERIFIED" : "REJECTED",
            shop);
    row = db.one("select * from hx_merchant_onboarding where shop_id=?", shop);
    boolean ready = verified(row) && gateway.ready(str(row, "merchant_no"));
    db.jdbc()
        .update(
            "update hx_shop set merchant_no=?,payment_ready=? where id=?",
            ready ? str(row, "merchant_no") : null,
            ready,
            shop);
    db.audit(
        actor,
        shop,
        "MERCHANT_VERIFY",
        String.valueOf(shop),
        null,
        map("stage", stage, "approve", approve, "reason", reason, "paymentReady", ready));
    return status(actor, shop);
  }
}
