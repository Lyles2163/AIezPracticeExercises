package org.leon.authmodule.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.leon.authmodule.mapper.AuthMapper;
import org.leon.authmodule.pojo.Users;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalUserControllerTest {

    private final AuthMapper authMapper = mock(AuthMapper.class);

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new InternalUserController(authMapper)).build();
    }

    @DisplayName("返回角色与手机号，且响应体不含密码哈希")
    @Test
    void shouldReturnProfileWithoutPassword() throws Exception {
        Users users = new Users();
        users.setId(10L);
        users.setUsername("test001");
        users.setRoleId("2");
        users.setPhone("13631155285");
        users.setStatus(1);
        users.setPassword_hash("$2a$10$hash");
        when(authMapper.selectByUserId(10L)).thenReturn(users);

        mockMvc().perform(get("/auth/internal/users/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleId").value("2"))
                .andExpect(jsonPath("$.phone").value("13631155285"))
                .andExpect(jsonPath("$.password_hash").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @DisplayName("用户不存在返回 404")
    @Test
    void shouldReturn404WhenMissing() throws Exception {
        when(authMapper.selectByUserId(999999L)).thenReturn(null);

        mockMvc().perform(get("/auth/internal/users/999999"))
                .andExpect(status().isNotFound());
    }
}
