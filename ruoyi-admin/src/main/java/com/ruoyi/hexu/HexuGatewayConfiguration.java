package com.ruoyi.hexu;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
@Configuration
public class HexuGatewayConfiguration {
 @Bean @ConditionalOnMissingBean(PaymentGateway.class)
 public PaymentGateway paymentGateway(){return new UnconfiguredPaymentGateway();}
 @Bean @ConditionalOnMissingBean(WechatIdentityGateway.class)
 public WechatIdentityGateway wechatIdentityGateway(){return new UnconfiguredWechatIdentityGateway();}
 @Bean @ConditionalOnMissingBean(TrackingGateway.class)
 public TrackingGateway trackingGateway(){return new TrackingGateway(){};}
}
