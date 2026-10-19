package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.RequestPostRepository;
import com.hamonsoft.netismaker.service.GitLabProjectClient;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REPO 추가 요청 관리자 API (스펙 2026-10-19). GitLab은 {@link GitLabProjectClient} 목으로 대체한다.
 * 시드 사용자 user1, admin1 (init-test-schema.sql).
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = "app.gitlab.base-url=https://gitlab.hamon.vip")
class RepoRequestApiIntegrationTest {

    private static final String ALIAS = "netis-collector";

    @Autowired private MockMvc mvc;
    @Autowired private RequestPostRepository posts;
    @Autowired private RepoCatalogRepository catalog;
    @MockitoBean private GitLabProjectClient client;

    @BeforeEach @AfterEach
    void clean() {
        // 요청을 먼저 지운다(request_post.repo_catalog_id는 ON DELETE SET NULL이라 순서는 무관하지만 의도를 분명히).
        // V14 시드(Netis7.0)는 건드리지 않고 이 테스트가 만든 별칭만 지운다.
        posts.deleteAll();
        catalog.findByAlias(ALIAS).ifPresent(catalog::delete);
    }

    private static RequestPostProcessor user(String username) {
        return jwt().jwt(b -> b.claim("username", username).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(b -> b.claim("username", "admin1").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private long createRepoRequest(RequestPostProcessor who) throws Exception {
        String json = mvc.perform(post("/api/requests").with(who).contentType(APPLICATION_JSON)
                        .content("{\"kind\":\"REPO\",\"title\":\"collector 레포 추가\",\"content\":\"netis-collector 부탁드립니다\","
                                + "\"repoHint\":\"netis/netis-collector\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    @Test
    void non_admin_forbidden() throws Exception {
        long id = createRepoRequest(user("user1"));

        mvc.perform(get("/api/admin/requests/" + id + "/repo-candidates").with(user("user1")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/gitlab-projects").param("search", "collector").with(user("user1")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/requests/" + id + "/add-repo").with(user("user1"))
                        .contentType(APPLICATION_JSON).content("{\"gitlabProjectId\":5}"))
                .andExpect(status().isForbidden());

        // 아무것도 바뀌지 않았다.
        mvc.perform(get("/api/requests/" + id).with(user("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void candidates_503_when_gitlab_unconfigured() throws Exception {
        long id = createRepoRequest(user("user1"));
        when(client.configured()).thenReturn(false);

        mvc.perform(get("/api/admin/requests/" + id + "/repo-candidates").with(admin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("사내 GitLab이 설정되지 않았습니다"));

        // 후보 조회 실패가 요청 자체의 조회에는 영향을 주지 않는다.
        mvc.perform(get("/api/requests/" + id).with(user("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void add_repo_end_to_end() throws Exception {
        long id = createRepoRequest(user("user1"));
        when(client.configured()).thenReturn(true);
        when(client.findById(5L)).thenReturn(Optional.of(new GitLabProjectClient.GitLabProject(
                5L, ALIAS, "netis/netis-collector", "main", "수집기")));

        mvc.perform(post("/api/admin/requests/" + id + "/add-repo").with(admin())
                        .contentType(APPLICATION_JSON).content("{\"gitlabProjectId\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.statusLabel").value("처리완료"))
                .andExpect(jsonPath("$.repoAlias").value(ALIAS))
                .andExpect(jsonPath("$.repoGitUrl").value("https://gitlab.hamon.vip/netis/netis-collector.git"));

        mvc.perform(get("/api/admin/repo-catalog").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].alias", hasItem(ALIAS)));

        // 이미 처리된 요청에 다시 등록하면 409.
        mvc.perform(post("/api/admin/requests/" + id + "/add-repo").with(admin())
                        .contentType(APPLICATION_JSON).content("{\"gitlabProjectId\":5}"))
                .andExpect(status().isConflict());
    }
}
