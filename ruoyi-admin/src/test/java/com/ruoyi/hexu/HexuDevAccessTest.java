package com.ruoyi.hexu;

import static org.junit.jupiter.api.Assertions.*;

import com.ruoyi.framework.config.ResourcesConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class HexuDevAccessTest {
    private static final String KEY = "local-sandbox-key-at-least-24-chars";

    private HexuDevController controller() {
        HexuDevController controller = new HexuDevController(null, null, null, null);
        ReflectionTestUtils.setField(controller, "key", KEY);
        return controller;
    }

    private MockHttpServletRequest request(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/hexu/dev/login");
        request.setRemoteAddr("127.0.0.1");
        if (origin != null) request.addHeader("Origin", origin);
        return request;
    }

    @Test void browserCannotUseFixedMiniProgramHeader() {
        MockHttpServletRequest request = request("http://127.0.0.1:4177");
        request.addHeader("X-Hexu-Mp-Dev", "1");
        assertThrows(BusinessException.class, () -> controller().check(request));

        MockHttpServletRequest external = request("https://attacker.example");
        external.addHeader("X-Hexu-Dev-Key", KEY);
        assertThrows(BusinessException.class, () -> controller().check(external));
    }

    @Test void nativeLocalRequestAndTrustedKeyStillWork() {
        MockHttpServletRequest nativeRequest = request(null);
        nativeRequest.addHeader("X-Hexu-Mp-Dev", "1");
        assertDoesNotThrow(() -> controller().check(nativeRequest));

        MockHttpServletRequest proxyRequest = request("http://127.0.0.1:4177");
        proxyRequest.addHeader("X-Hexu-Dev-Key", KEY);
        assertDoesNotThrow(() -> controller().check(proxyRequest));
    }

    @Test void crossSiteFetchMetadataIsRejected() {
        MockHttpServletRequest request = request(null);
        request.addHeader("X-Hexu-Mp-Dev", "1");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertThrows(BusinessException.class, () -> controller().check(request));
    }

    @Test void preflightOnlyAllowsTrustedOrigins() throws Exception {
        ResourcesConfig config = new ResourcesConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://127.0.0.1:4177,http://localhost:4177");

        MockHttpServletResponse denied = preflight(config, "https://attacker.example");
        assertEquals(403, denied.getStatus());
        assertNull(denied.getHeader("Access-Control-Allow-Origin"));

        MockHttpServletResponse allowed = preflight(config, "http://127.0.0.1:4177");
        assertEquals(200, allowed.getStatus());
        assertEquals("http://127.0.0.1:4177", allowed.getHeader("Access-Control-Allow-Origin"));
    }

    private MockHttpServletResponse preflight(ResourcesConfig config, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/hexu/dev/login");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        config.corsFilter().doFilter(request, response, new MockFilterChain());
        return response;
    }
}
