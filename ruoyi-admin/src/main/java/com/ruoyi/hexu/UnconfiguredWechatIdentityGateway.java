package com.ruoyi.hexu;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import java.util.Map;
public class UnconfiguredWechatIdentityGateway implements WechatIdentityGateway {
 public Map<String,Object> exchangeCode(String code){throw new BusinessException("微信登录渠道尚未配置，请在服务端配置公司AppID和凭据");}
 public Map<String,Object> authorizedPhone(String code){throw new BusinessException("微信手机号授权渠道尚未配置");}
}
