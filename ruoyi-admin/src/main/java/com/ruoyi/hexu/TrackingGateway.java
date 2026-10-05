package com.ruoyi.hexu;

import static com.ruoyi.hexu.HexuStore.map;

import java.util.*;

/** A configured carrier adapter must return authenticated events for the exact waybill. */
public interface TrackingGateway {
  default Map<String, Object> query(String carrier, String tracking) {
    return map("status", "UNAVAILABLE", "events", Collections.emptyList(), "contact", "");
  }

  default boolean configured() {
    return false;
  }
}
