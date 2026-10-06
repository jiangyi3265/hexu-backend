package com.ruoyi.hexu;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class HexuDemoContextTest {
  @Configuration
  @Import(HexuGatewayConfiguration.class)
  @ComponentScan(basePackageClasses = HexuDemoPaymentGateway.class, useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
          classes = {HexuDemoPaymentGateway.class, HexuDemoTrackingGateway.class,
              HttpsTrackingGateway.class, HexuFinanceService.class}))
  static class Config {
    @Bean DataSource dataSource() {
      return new DriverManagerDataSource("jdbc:h2:mem:hexu-demo-context;MODE=MySQL", "sa", "");
    }
    @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
    @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    @Bean HexuStore store(JdbcTemplate jdbc, ObjectMapper mapper) { return new HexuStore(jdbc, mapper); }
  }

  @Test void demonstrationGatewayRequiresProdProfileAndExplicitSwitchWithoutRealPayments() {
    assertGateway("hexu-prod", true, false, true);
    assertGateway("hexu-prod", false, false, false);
    assertGateway("hexu-prod", true, true, false);
    assertGateway("hexu-dev", true, false, false);
  }

  @Test void demonstrationTrackingDoesNotReplaceConfiguredCarrier() {
    assertTracking(true, false, true);
    assertTracking(true, true, false);
    assertTracking(false, false, false);
  }

  private void assertTracking(boolean demo, boolean carrier, boolean expected) {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("hexu-prod");
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("switches",
          java.util.Map.of("hexu.demo.enabled", demo, "hexu.logistics.enabled", carrier)));
      context.register(Config.class);
      context.refresh();
      assertEquals(expected, context.containsBean("hexuDemoTrackingGateway"));
      if (carrier) assertTrue(context.getBean(TrackingGateway.class) instanceof HttpsTrackingGateway);
    }
  }

  private void assertGateway(String profile, boolean demo, boolean real, boolean expected) {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles(profile);
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("switches",
          java.util.Map.of("hexu.demo.enabled", demo, "hexu.wechat-pay.enabled", real)));
      context.register(Config.class);
      context.refresh();
      assertEquals(expected, context.containsBean("hexuDemoPaymentGateway"));
    }
  }
}
