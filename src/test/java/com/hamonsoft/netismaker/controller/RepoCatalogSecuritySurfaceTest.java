package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class RepoCatalogSecuritySurfaceTest {

    @Autowired
    private MockMvc mvc;

    private static RequestPostProcessor userJwt() {
        return jwt().jwt(b -> b.claim("user_id", "user1").claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor adminJwt() {
        return jwt().jwt(b -> b.claim("user_id", "admin").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void list_for_users_requires_auth() throws Exception {
        mvc.perform(get("/api/repo-catalog")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticated_user_can_list_enabled() throws Exception {
        mvc.perform(get("/api/repo-catalog").with(userJwt())).andExpect(status().isOk());
    }

    @Test
    void admin_list_forbidden_for_non_admin() throws Exception {
        mvc.perform(get("/api/admin/repo-catalog").with(userJwt())).andExpect(status().isForbidden());
    }

    @Test
    void admin_can_list_all() throws Exception {
        mvc.perform(get("/api/admin/repo-catalog").with(adminJwt())).andExpect(status().isOk());
    }

    @Test
    void create_forbidden_for_non_admin() throws Exception {
        mvc.perform(post("/api/admin/repo-catalog").with(userJwt())
                        .contentType("application/json")
                        .content("{\"alias\":\"X\",\"gitUrl\":\"owner/repo\"}"))
                .andExpect(status().isForbidden());
    }
}
