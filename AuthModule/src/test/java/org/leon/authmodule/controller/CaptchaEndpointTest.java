package org.leon.authmodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.authmodule.service.CaptchaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CaptchaEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    /** Mock 掉 Service：本测试只验"路由可达 + 响应形状 + 未被安全链拦下"，不验图形码本身 */
    @MockBean
    private CaptchaService captchaService;

    @DisplayName("未登录也能取到验证码图，且返回体带 captchaKey 与 base64")
    @Test
    void captchaEndpointIsPubliclyReachable() throws Exception {
        when(captchaService.generate())
                .thenReturn(new CaptchaService.CaptchaImage("k-fixed", "data:image/png;base64,AAAA"));

        mockMvc.perform(get("/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.data.captchaKey").value("k-fixed"))
                .andExpect(jsonPath("$.data.imageBase64").value("data:image/png;base64,AAAA"));
    }
}
