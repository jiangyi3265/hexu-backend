package com.ruoyi.hexu;
import java.util.Map;
/** Implement with company AppID credentials; never accept an openId supplied directly by a client. */
public interface WechatIdentityGateway {
 Map<String,Object> exchangeCode(String loginCode);
 Map<String,Object> authorizedPhone(String phoneCode);
}
