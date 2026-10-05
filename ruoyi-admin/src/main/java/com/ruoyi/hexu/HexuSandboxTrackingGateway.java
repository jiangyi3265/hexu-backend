package com.ruoyi.hexu;

import static com.ruoyi.hexu.HexuStore.map;

import java.util.Collections;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Synthetic tracking only for visibly named test waybills; never impersonates a carrier feed. */
@Component
@Primary
@Profile("hexu-dev")
@ConditionalOnProperty(name = "hexu.sandbox.enabled", havingValue = "true")
public class HexuSandboxTrackingGateway implements TrackingGateway {
  @Override
  public Map<String, Object> query(String carrier, String tracking) {
    if (tracking == null || !tracking.matches("(?i)^(TEST|SANDBOX)[A-Za-z0-9_-]{4,36}$"))
      return map("status", "UNAVAILABLE", "events", Collections.emptyList(),
          "contact", "", "sandbox", true, "message", "未连接真实承运商轨迹");
    return map("status", "IN_TRANSIT", "events",
        Collections.singletonList(map("time", "", "description", "本地模拟运单已录入；无真实运输轨迹")),
        "contact", "", "sandbox", true, "message", "本地模拟，非承运商数据");
  }

  @Override
  public boolean configured() {
    return false;
  }
}
