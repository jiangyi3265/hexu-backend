package com.ruoyi.hexu;

import com.ruoyi.common.exception.base.BaseException;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionResponseTest {
    @RestController
    static class FaultController {
        @GetMapping("/fault/runtime")
        public Object runtime() {
            throw new RuntimeException("select * from hx_audit in hexu_integration");
        }

        @GetMapping("/fault/checked")
        public Object checked() throws Exception {
            throw new Exception("SQL table hx_order is unavailable");
        }

        @GetMapping("/fault/login")
        public Object login() {
            throw new BaseException("user", "用户不存在/密码错误");
        }
    }

    @Test
    void userFacingLoginFailuresKeepTheirMessage() throws Exception {
        MockMvc http = MockMvcBuilders.standaloneSetup(new FaultController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        http.perform(get("/fault/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("用户不存在/密码错误"));
    }

    @Test
    void unexpectedFailuresReturnHttp500WithoutDatabaseDetails() throws Exception {
        MockMvc http = MockMvcBuilders.standaloneSetup(new FaultController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String path : new String[] {"/fault/runtime", "/fault/checked"}) {
            http.perform(get(path))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.msg").value("服务暂不可用，请稍后重试"));
        }
    }
}
