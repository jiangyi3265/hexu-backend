package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Server-side mini-program identity exchange. Tokens and session keys are never sent to the client.
 */
@Component
@ConditionalOnProperty(name = "hexu.wechat.app-id")
public class WechatApiIdentityGateway implements WechatIdentityGateway {
  private final String appId, secret;
  private final ObjectMapper json;
  private final RestTemplate http;
  private volatile String accessToken;
  private volatile Instant tokenExpiresAt = Instant.EPOCH;

  public WechatApiIdentityGateway(
      @Value("${hexu.wechat.app-id}") String appId,
      @Value("${hexu.wechat.app-secret:}") String secret,
      ObjectMapper json) {
    this.appId = appId;
    this.secret = secret;
    this.json = json;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(5000);
    factory.setReadTimeout(8000);
    this.http = new RestTemplate(factory);
  }

  private void configured() {
    require(
        appId.matches("wx[0-9a-fA-F]{16}") && !secret.isEmpty(), "小程序 AppID 或 AppSecret 尚未正确配置");
  }

  private String encode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode response(String url, HttpMethod method, String body) {
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      ResponseEntity<String> result =
          http.exchange(url, method, new HttpEntity<>(body, headers), String.class);
      JsonNode data = json.readTree(result.getBody());
      require(data.path("errcode").asInt(0) == 0, "微信接口拒绝请求：" + data.path("errcode").asText());
      return data;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException("微信身份服务暂不可用，请稍后重试");
    }
  }

  @Override
  public Map<String, Object> exchangeCode(String loginCode) {
    configured();
    require(loginCode != null && loginCode.length() > 0 && loginCode.length() <= 256, "微信登录凭证无效");
    JsonNode data =
        response(
            "https://api.weixin.qq.com/sns/jscode2session?appid="
                + encode(appId)
                + "&secret="
                + encode(secret)
                + "&js_code="
                + encode(loginCode)
                + "&grant_type=authorization_code",
            HttpMethod.GET,
            null);
    String openId = data.path("openid").asText();
    require(!openId.isEmpty(), "微信登录未返回 OpenID");
    return map("appId", appId, "openId", openId);
  }

  private synchronized String token() {
    configured();
    if (accessToken != null && Instant.now().isBefore(tokenExpiresAt)) return accessToken;
    JsonNode data =
        response(
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid="
                + encode(appId)
                + "&secret="
                + encode(secret),
            HttpMethod.GET,
            null);
    String token = data.path("access_token").asText();
    long lifetime = data.path("expires_in").asLong();
    require(!token.isEmpty() && lifetime > 60, "微信 AccessToken 无效");
    accessToken = token;
    tokenExpiresAt = Instant.now().plusSeconds(lifetime - 60);
    return token;
  }

  @Override
  public Map<String, Object> authorizedPhone(String phoneCode) {
    configured();
    require(phoneCode != null && phoneCode.length() > 0 && phoneCode.length() <= 256, "手机号授权凭证无效");
    String payload;
    try {
      payload = json.writeValueAsString(map("code", phoneCode));
    } catch (Exception e) {
      throw new BusinessException("手机号授权请求格式错误");
    }
    JsonNode data =
        response(
            "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token="
                + encode(token()),
            HttpMethod.POST,
            payload);
    String phone = data.path("phone_info").path("purePhoneNumber").asText();
    require(phone.matches("1[3-9][0-9]{9}"), "微信未返回有效手机号");
    return map("phone", phone);
  }
}
