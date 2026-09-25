package com.ruoyi.hexu;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;
public class UnconfiguredPaymentGateway implements PaymentGateway {
        private Map<String,Object> missing(){throw new BusinessException("支付渠道未配置，未向外部提交付款或退款");}
        public Map<String,Object> createPayment(Map<String,Object> payment,String openId){return missing();}
        public Map<String,Object> submitRefund(Map<String,Object> refund,String merchantNo){return missing();}
        public Map<String,Object> submitPayout(Map<String,Object> withdrawal,String merchantNo){return missing();}
        public Map<String,Object> query(String operation,String id,String merchantNo){return missing();}
}
