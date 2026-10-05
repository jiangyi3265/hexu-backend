package com.ruoyi.hexu;

import static com.ruoyi.hexu.BusinessException.require;
import static com.ruoyi.hexu.HexuStore.*;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** HTTPS carrier-adapter contract: exact carrier/tracking echo plus status/events. */
@Component
@ConditionalOnProperty(name = "hexu.logistics.enabled", havingValue = "true")
public class HttpsTrackingGateway implements TrackingGateway {
  @Value("${hexu.logistics.endpoint:}")
  private String endpoint;

  @Value("${hexu.logistics.token:}")
  private String token;

  private final RestTemplate http;

  public HttpsTrackingGateway() {
    SimpleClientHttpRequestFactory factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(HttpURLConnection connection, String method)
              throws IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
          }
        };
    factory.setConnectTimeout(5000);
    factory.setReadTimeout(8000);
    http = new RestTemplate(factory);
  }

  @Override
  public boolean configured() {
    try {
      URI uri = URI.create(endpoint);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && !token.isEmpty();
    } catch (Exception e) {
      return false;
    }
  }

  private String encode(String text) {
    try {
      return URLEncoder.encode(text, "UTF-8");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> query(String carrier, String tracking) {
    if (!configured()) return TrackingGateway.super.query(carrier, tracking);
    require(
        carrier != null
            && carrier.length() > 0
            && carrier.length() <= 80
            && tracking != null
            && tracking.matches("[A-Za-z0-9]{8,40}"),
        "快递公司或运单号格式无效");
    try {
      String separator = endpoint.contains("?") ? "&" : "?";
      String url =
          endpoint + separator + "carrier=" + encode(carrier) + "&tracking=" + encode(tracking);
      HttpHeaders headers = new HttpHeaders();
      headers.setBearerAuth(token);
      headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
      ResponseEntity<Map> response =
          http.exchange(url, HttpMethod.GET, new HttpEntity<Void>(headers), Map.class);
      require(
          response.getStatusCode().is2xxSuccessful() && response.getBody() != null, "承运商轨迹服务无可用结果");
      Map<String, Object> body = response.getBody();
      require(
          carrier.equals(str(body, "carrier")) && tracking.equals(str(body, "tracking")),
          "承运商返回了其他运单的轨迹");
      String status = str(body, "status");
      require(Arrays.asList("IN_TRANSIT", "DELIVERED", "EXCEPTION").contains(status), "承运商轨迹状态无效");
      Object raw = body.get("events");
      require(raw instanceof List && ((List<?>) raw).size() <= 200, "承运商轨迹数量无效");
      List<Map<String, Object>> events = new ArrayList<>();
      for (Object item : (List<?>) raw) {
        require(item instanceof Map, "承运商轨迹格式无效");
        Map<String, Object> event = (Map<String, Object>) item;
        String time = str(event, "time"), description = str(event, "description");
        require(time.length() <= 40 && description.length() <= 500, "承运商轨迹字段过长");
        events.add(map("time", time, "description", description));
      }
      return map(
          "status",
          status,
          "events",
          events,
          "contact",
          str(body, "contact").length() <= 80 ? str(body, "contact") : "");
    } catch (Exception e) {
      return TrackingGateway.super.query(carrier, tracking);
    }
  }
}
