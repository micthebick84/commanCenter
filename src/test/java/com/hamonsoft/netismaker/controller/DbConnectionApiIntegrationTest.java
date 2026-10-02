package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.repository.DbConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DB 접속정보 API 표면 (스펙 2026-10-02 §5.1·§10). 레포 카탈로그 id 1 = V14 시드 'Netis7.0'. */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest(properties = {"app.db-secret.key=it-key", "app.db-secret.salt=0123456789abcdef"})
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class DbConnectionApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private DbConnectionRepository repo;

    private static final String USER_BODY = "{\"scope\":\"USER\",\"repoCatalogId\":1,\"name\":\"내 DB\",\"dbType\":\"POSTGRESQL\","
            + "\"host\":\"127.0.0.1\",\"port\":1,\"databaseName\":\"app\",\"username\":\"reader\",\"password\":\"Pw-123456\"}";

    @BeforeEach void clean() { repo.deleteAll(); }

    private static RequestPostProcessor user(String id) {
        return jwt().jwt(b -> b.claim("username", id).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(b -> b.claim("username", "admin1").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void user_creates_own_row_and_password_never_comes_back() throws Exception {
        mvc.perform(post("/api/db-connections").with(user("user1")).contentType(APPLICATION_JSON).content(USER_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().string(not(containsString("Pw-123456"))))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(jsonPath("$.mine").value(true));
        mvc.perform(get("/api/db-connections").param("repoCatalogId", "1").with(user("user1")))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(get("/api/db-connections").param("repoCatalogId", "1").with(user("user2")))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void repo_scope_needs_admin() throws Exception {
        String body = USER_BODY.replace("\"USER\"", "\"REPO\"");
        mvc.perform(post("/api/db-connections").with(user("user1")).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/db-connections").with(admin()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/db-connections").param("repoCatalogId", "1").with(user("user2")))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void test_endpoint_reports_failure_without_password() throws Exception {
        mvc.perform(post("/api/db-connections/test").with(user("user1")).contentType(APPLICATION_JSON)
                        .content("{\"dbType\":\"POSTGRESQL\",\"host\":\"127.0.0.1\",\"port\":1,\"databaseName\":\"app\","
                                + "\"username\":\"reader\",\"password\":\"Pw-123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(content().string(not(containsString("Pw-123456"))));
    }

    @Test
    void injection_in_host_is_400() throws Exception {
        mvc.perform(post("/api/db-connections").with(user("user1")).contentType(APPLICATION_JSON)
                        .content(USER_BODY.replace("127.0.0.1", "127.0.0.1/evil")))
                .andExpect(status().isBadRequest());
    }
}
