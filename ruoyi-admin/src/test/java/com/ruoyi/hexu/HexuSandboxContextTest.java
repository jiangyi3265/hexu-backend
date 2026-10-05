package com.ruoyi.hexu;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class HexuSandboxContextTest {
  @Configuration
  @EnableTransactionManagement
  @Import(HexuGatewayConfiguration.class)
  @ComponentScan(basePackageClasses = HexuSandboxPaymentGateway.class, useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
          classes = {HexuSandboxPaymentGateway.class, HexuFinanceService.class}))
  static class Config {
    @Bean DataSource dataSource() {
      return new DriverManagerDataSource("jdbc:h2:mem:hexu-sandbox-context;MODE=MySQL", "sa", "");
    }
    @Bean JdbcTemplate jdbc(DataSource dataSource) { return new JdbcTemplate(dataSource); }
    @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    @Bean HexuStore store(JdbcTemplate jdbc, ObjectMapper mapper) {
      return new HexuStore(jdbc, mapper);
    }
    @Bean PlatformTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }
  }

  @Test void sandboxGatewayAndFinanceStartTogetherOnlyUnderExplicitLocalProfile() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("hexu-dev");
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sandbox",
          java.util.Map.of("hexu.sandbox.enabled", "true")));
      context.register(Config.class);
      context.refresh();
      assertTrue(context.containsBean("hexuSandboxPaymentGateway"));
      assertNotNull(context.getBean(PaymentGateway.class));
      assertNotNull(context.getBean(HexuFinanceService.class));
      assertFalse(context.getBean(PaymentGateway.class).ready("SANDBOX-ANXI"));
    }
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("hexu-dev");
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sandbox",
          java.util.Map.of("hexu.sandbox.enabled", "false")));
      context.register(Config.class);
      context.refresh();
      assertFalse(context.containsBean("hexuSandboxPaymentGateway"));
    }
  }
}
