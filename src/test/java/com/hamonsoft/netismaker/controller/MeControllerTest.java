package com.hamonsoft.netismaker.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.hamonsoft.netismaker.config.CorsConfig;
import com.hamonsoft.netismaker.config.SecurityConfig;
import com.hamonsoft.netismaker.config.WorkerApiKeyFilter;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MeController.class)
@Import({SecurityConfig.class, CorsConfig.class, WorkerApiKeyFilter.class})
@AutoConfigureMockMvc
class MeControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void unauthenticated_returns_401() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticated_returns_jwt_claims() throws Exception {
        mvc.perform(get("/api/me").with(jwt()
                        .jwt(builder -> builder
                                .claim("user_id", 42L)
                                .claim("username", "khlee84")
                                .claim("email", "khlee84@hamonsoft.co.kr")
                                .claim("authorities", java.util.List.of("ROLE_USER")))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(42))
                .andExpect(jsonPath("$.username").value("khlee84"))
                .andExpect(jsonPath("$.email").value("khlee84@hamonsoft.co.kr"))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"));
    }

    @Test
    void admin_role_appears_in_authorities() throws Exception {
        mvc.perform(get("/api/me").with(jwt()
                        .jwt(builder -> builder
                                .claim("user_id", 1L)
                                .claim("authorities", java.util.List.of("ROLE_ADMIN")))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"));
    }
}
