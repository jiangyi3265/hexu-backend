package com.ruoyi.hexu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.http.HttpHeaders;
import com.wechat.pay.java.core.http.HttpMethod;
import com.wechat.pay.java.core.http.HttpRequest;
import com.wechat.pay.java.core.http.HttpResponse;
import com.wechat.pay.java.core.http.JsonRequestBody;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.ruoyi.hexu.HexuStore.str;

/**
 * Raw HTTP adapter for the 2025+ WeChat merchant-transfer API.
 * The official Java SDK 0.2.x does not expose the new fund-app APIs, so the
 * SDK core client is used for request signing, response verification and TLS.
 */
final class WechatTransferClient {
  private final Config config;
  private final ObjectMapper json;
  private final String appId;
  private final String notifyUrl;
  private final String transferSceneId;
  private final String subMerchantId;
  private final boolean partner;

  WechatTransferClient(
      Config config,
      ObjectMapper json,
      String appId,
      String notifyUrl,
      String transferSceneId,
      String subMerchantId,
      boolean partner) {
    this.config = config;
    this.json = json;
    this.appId = appId;
    this.notifyUrl = notifyUrl;
    this.transferSceneId = transferSceneId;
    this.subMerchantId = subMerchantId;
    this.partner = partner;
  }

  Map<String, Object> create(
      String outBillNo,
      String openId,
      long amount,
      String userName,
      String remark,
      List<Map<String, String>> sceneReportInfos) {
    requireSafe(outBillNo != null && outBillNo.matches("[A-Za-z0-9]{1,32}"), "提现渠道单号格式无效");
    requireSafe(openId != null && !openId.trim().isEmpty() && openId.length() <= 64, "收款用户身份未绑定微信");
    requireSafe(amount > 0, "提现金额必须为正数");
    requireSafe(appId.matches("wx[0-9a-fA-F]{16}"), "微信小程序 AppID 未配置");
    requireSafe(transferSceneId != null && transferSceneId.matches("[A-Za-z0-9_-]{1,36}"), "微信转账场景未配置");
    requireSafe(notifyUrl != null && notifyUrl.startsWith("https://") && !notifyUrl.contains("?"), "微信转账回调地址必须为 HTTPS");
    String trimmedRemark = remark == null || remark.trim().isEmpty() ? "收益提现" : remark.trim();
    requireSafe(trimmedRemark.length() <= 32, "微信转账备注不能超过32个字符");
    Map<String, Object> body = new LinkedHashMap<>();
    if (partner) {
      requireSafe(subMerchantId != null && subMerchantId.matches("[0-9]{8,20}"), "微信子商户号未配置");
      body.put("sub_mchid", subMerchantId);
    }
    body.put("appid", appId);
    body.put("out_bill_no", outBillNo);
    body.put("transfer_scene_id", transferSceneId);
    body.put("openid", openId);
    if (userName != null && !userName.trim().isEmpty()) {
      requireSafe(amount >= 200000 || userName.length() <= 128, "微信大额转账必须提交真实收款人姓名");
      body.put("user_name", config.createEncryptor().encrypt(userName.trim()));
    } else {
      requireSafe(amount < 200000, "提现金额达到2000元时必须提交收款人姓名");
    }
    body.put("transfer_amount", amount);
    body.put("transfer_remark", trimmedRemark);
    body.put("notify_url", notifyUrl);
    body.put("user_recv_perception", "分销收益提现");
    if (sceneReportInfos != null && !sceneReportInfos.isEmpty()) {
      List<Map<String, String>> safeInfos = new ArrayList<>();
      for (Map<String, String> info : sceneReportInfos) {
        String type = info == null || info.get("info_type") == null ? "" : info.get("info_type").trim();
        String content = info == null || info.get("info_content") == null ? "" : info.get("info_content").trim();
        requireSafe(!type.isEmpty() && type.length() <= 15 && !content.isEmpty() && content.length() <= 32, "微信转账场景报备信息无效");
        safeInfos.add(new LinkedHashMap<String, String>() {{ put("info_type", type); put("info_content", content); }});
      }
      body.put("transfer_scene_report_infos", safeInfos);
    } else {
      throw new BusinessException("微信转账场景报备信息未配置");
    }
    return execute(HttpMethod.POST, path(partner
        ? "/v3/fund-app/mch-transfer/partner/transfer-bills"
        : "/v3/fund-app/mch-transfer/transfer-bills"), body);
  }

  Map<String, Object> query(String outBillNo) {
    requireSafe(outBillNo != null && outBillNo.matches("[A-Za-z0-9]{1,32}"), "提现渠道单号格式无效");
    String path = partner
        ? "/v3/fund-app/mch-transfer/partner/transfer-bills/out-bill-no/" + outBillNo + "?sub_mchid=" + subMerchantId
        : "/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/" + outBillNo;
    return execute(HttpMethod.GET, path, null);
  }

  private String path(String value) { return value; }

  @SuppressWarnings("unchecked")
  private Map<String, Object> execute(HttpMethod method, String path, Map<String, Object> body) {
    try {
      HttpRequest.Builder request = new HttpRequest.Builder()
          .httpMethod(method)
          .url("https://api.mch.weixin.qq.com" + path)
          .headers(new HttpHeaders());
      request.addHeader("Accept", "application/json");
      if (body != null) {
        request.addHeader("Content-Type", "application/json");
        request.body(new JsonRequestBody.Builder().body(json.writeValueAsString(body)).build());
      }
      HttpClient client = new DefaultHttpClientBuilder().config(config).build();
      HttpResponse<Map> response = client.execute(request.build(), Map.class);
      Map<String, Object> result = response.getServiceResponse();
      if (result == null) throw new BusinessException("微信转账渠道返回空结果");
      return result;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException("微信转账渠道请求失败，请保留原提现单号后查询：" + e.getMessage());
    }
  }

  private static void requireSafe(boolean condition, String message) {
    if (!condition) throw new BusinessException(message);
  }
}
