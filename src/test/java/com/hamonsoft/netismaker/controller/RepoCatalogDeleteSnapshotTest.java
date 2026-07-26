package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.dto.TaskCreateRequest;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V14 ON DELETE SET NULL 동작 검증.
 * 카탈로그 항목 삭제 후 작업의 git_url/repo_alias 스냅샷은 보존되고
 * repo_catalog_id만 null이 되어야 한다.
 *
 * 환경변수 RUN_TESTCONTAINERS=true 일 때만 실행 (CI 전용).
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
class RepoCatalogDeleteSnapshotTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private RepoCatalogRepository repoCatalogRepo;

    @BeforeEach
    void cleanTasks() {
        // 공유 컨테이너 — 다른 클래스가 남긴 자식 row(task_design/interview_session)가
        // task 삭제를 FK로 막지 않도록 자식 먼저 삭제
        designRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt(String userId) {
        return jwt()
                .jwt(b -> b.claim("username", userId).claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void DELETE_repo_catalog_sets_task_snapshot_catalog_id_to_null_and_preserves_alias_and_git_url() throws Exception {
        // V14 시드('Netis7.0')는 다른 게이트 클래스들이 카탈로그 id=1로 공유하므로 삭제 대상으로 쓰지 않는다.
        // 전용 카탈로그 항목을 만들어 그것을 삭제한다.
        String alias = "del-snap-" + System.nanoTime();
        RepoCatalogEntry cat = RepoCatalogEntry.create(alias, "https://github.com/hamonsoft/" + alias,
                "github", "hamonsoft/" + alias, "main", "삭제 스냅샷 테스트", "admin1");
        Long catalogId = repoCatalogRepo.save(cat).getId();

        // 해당 카탈로그 항목으로 작업 등록
        var req = new TaskCreateRequest(catalogId, "main", "스냅샷 보존 테스트", "카탈로그 삭제 후 스냅샷 확인");
        String location = mvc.perform(post("/api/tasks").with(adminJwt("admin1"))
                        .contentType(APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        Long taskId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        // 작업 등록 후 스냅샷 컬럼 확인
        var taskBefore = taskRepo.findById(taskId).orElseThrow();
        assertThat(taskBefore.getRepoAlias()).isEqualTo(alias);
        assertThat(taskBefore.getGitUrl()).isNotNull();
        assertThat(taskBefore.getRepoCatalogId()).isEqualTo(catalogId);

        // 카탈로그 항목 삭제 — ON DELETE SET NULL이면 204, FK violation이면 500/409
        mvc.perform(delete("/api/admin/repo-catalog/" + catalogId).with(adminJwt("admin1")))
                .andExpect(status().isNoContent());

        // 삭제 후 작업 row 재조회: 스냅샷(git_url, repo_alias)은 보존, catalog_id는 null
        var taskAfter = taskRepo.findById(taskId).orElseThrow();
        assertThat(taskAfter.getRepoCatalogId())
                .as("카탈로그 삭제 후 repo_catalog_id는 null이어야 함")
                .isNull();
        assertThat(taskAfter.getRepoAlias())
                .as("repo_alias 스냅샷은 카탈로그 삭제 후에도 보존되어야 함")
                .isEqualTo(alias);
        assertThat(taskAfter.getGitUrl())
                .as("git_url 스냅샷은 카탈로그 삭제 후에도 보존되어야 함")
                .isNotNull();
    }
}
