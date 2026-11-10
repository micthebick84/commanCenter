package com.hamonsoft.netismaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.TestcontainersConfig;
import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.RepoCatalogRepository;
import com.hamonsoft.netismaker.repository.TaskAnalysisRepository;
import com.hamonsoft.netismaker.repository.TaskDesignRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import com.hamonsoft.netismaker.repository.TaskStatusHistoryRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 종료 부분 패치 흐름 통합 테스트 (스펙 2026-11-10). Testcontainers 기반 — RUN_TESTCONTAINERS=true 일 때만(CI).
 *
 * 승인(패치 모드 REQUIRED) → claim의 closePatch → MERGED(baseSha) → 패치 zip 업로드 → PATCH_BUILT → 나머지 단계 →
 * 작업종료 → 상세의 patch 칸 → 1회용 내려받기, 그리고 PATCH_BUILT 없이 끝낼 수 없다는 것과 '패치 없이 마무리' 경로.
 * 산출물 모드는 카탈로그 artifactEnabled를 켜지 않아 null이다(단계 5개). 메일은 이 흐름에 없다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = {
        "app.close.patch.enabled=true",
        "app.public-base-url=https://app.test"
})
class ClosePatchFlowIntegrationTest {

    private static final String WORKER_KEY = "X-Worker-API-Key";
    private static final String BASE_SHA = "89abcdef0123456789abcdef0123456789abcdef";

    /** 이 클래스 전용 첨부 루트 — 운영자 실제 ~/netis-maker/attachments를 건드리지 않는다. 컨텍스트를 만들 때만 생긴다. */
    private static Path attachmentRoot;

    @DynamicPropertySource
    static void attachmentDir(DynamicPropertyRegistry r) {
        try {
            attachmentRoot = Files.createTempDirectory("close-patch-it").toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path root = attachmentRoot;
        r.add("app.attachment.dir", root::toString);
    }

    @AfterAll
    static void removeAttachmentRoot() throws IOException {
        if (attachmentRoot != null) FileSystemUtils.deleteRecursively(attachmentRoot);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private TaskRepository taskRepo;
    @Autowired private TaskAnalysisRepository analysisRepo;
    @Autowired private TaskDesignRepository designRepo;
    @Autowired private InterviewSessionRepository sessionRepo;
    @Autowired private RepoCatalogRepository catalogRepo;
    @Autowired private TaskStatusHistoryRepository historyRepo;
    @Value("${app.worker.api-key}") private String apiKey;

    /** 테스트가 만든 카탈로그 항목 — 끝나면 지운다(작업의 repo_catalog_id는 ON DELETE SET NULL). */
    private Long catalogId;

    @BeforeEach
    void cleanTasks() {
        designRepo.deleteAll();
        analysisRepo.deleteAll();
        sessionRepo.deleteAll();
        taskRepo.deleteAll();
    }

    @AfterEach
    void removeCatalog() {
        if (catalogId != null) catalogRepo.deleteById(catalogId);
        catalogId = null;
    }

    private static RequestPostProcessor user(String id) {
        return jwt().jwt(b -> b.claim("username", id).claim("authorities", List.of("ROLE_USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(b -> b.claim("username", "admin").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private ResultActions claim() throws Exception {
        return mvc.perform(get("/worker/next-task").param("workerId", "w1").header(WORKER_KEY, apiKey));
    }

    private ResultActions reportProgress(Long id, String body) throws Exception {
        return mvc.perform(post("/worker/tasks/" + id + "/close-progress").header(WORKER_KEY, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void progress(Long id, String step) throws Exception {
        reportProgress(id, "{\"workerId\":\"w1\",\"step\":\"" + step + "\"}").andExpect(status().isNoContent());
    }

    private void merged(Long id) throws Exception {
        reportProgress(id, "{\"workerId\":\"w1\",\"step\":\"MERGED\","
                + "\"mergeCommitSha\":\"fedcba9876543210fedcba9876543210fedcba98\","
                + "\"baseSha\":\"" + BASE_SHA + "\"}").andExpect(status().isNoContent());
    }

    private ResultActions reportResult(Long id, String json) throws Exception {
        return mvc.perform(post("/worker/tasks/" + id + "/result").header(WORKER_KEY, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions reportClosed(Long id) throws Exception {
        return reportResult(id, "{\"workerId\":\"w1\",\"status\":\"CLOSED\"}");
    }

    private ResultActions uploadPatch(Long id, String sha256, int fileCount, byte[] body) throws Exception {
        return mvc.perform(put("/worker/tasks/" + id + "/patch").header(WORKER_KEY, apiKey)
                .param("workerId", "w1").param("fileCount", String.valueOf(fileCount))
                .header("X-Artifact-Sha256", sha256)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(body));
    }

    private void saveCatalog(long nano) {
        RepoCatalogEntry cat = RepoCatalogEntry.create("패치-it-" + nano,
                "https://github.com/owner/repo-" + nano + ".git",
                "github", "owner/repo-" + nano, null, null, "admin");
        cat.setPrebuildEnabled(true);
        cat.setPrebuildCommand("mvn -q package");
        cat.setPatchEnabled(true);
        catalogId = catalogRepo.save(cat).getId();
    }

    private Long deployedTask(long nano, String title) {
        Task t = Task.create("owner/repo-" + nano, "main", title, "d", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        t.setStatus(TaskStatus.DEPLOYED);
        t.setPrNumber(3);
        t.setPrUrl("https://github.com/owner/repo/pull/3");
        t.setHeadBranch("netismaker/task-x");
        t.setHeadSha("0123456789abcdef0123456789abcdef01234567");
        t.setRepoCatalogId(catalogId);
        return taskRepo.save(t).getId();
    }

    /** 등록자 종료 요청 → 관리자 승인. 승인 응답이 패치 모드 REQUIRED이고 이력 사유가 '(부분 패치)'로 끝나야 한다. */
    private void requestAndApprove(Long id) throws Exception {
        mvc.perform(post("/api/tasks/" + id + "/close-request").with(user("user1"))).andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + id + "/close/approve").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSE_PENDING"))
                .andExpect(jsonPath("$.close.patchMode").value("REQUIRED"));
        assertThat(historyRepo.findByTaskIdOrderByAtDescIdDesc(id, Limit.of(50)))
                .anyMatch(h -> h.getReason() != null && h.getReason().endsWith(" (부분 패치)"));
    }

    @Test
    void 승인부터_패치_업로드_종료_1회용_내려받기까지() throws Exception {
        long nano = System.nanoTime();
        saveCatalog(nano);
        Long id = deployedTask(nano, "패치 흐름");
        requestAndApprove(id);

        // ② claim — 패치 지시(카탈로그 미리 빌드 설정). 기준 커밋은 MERGED 보고 전이라 아직 없다
        claim().andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("CLOSE"))
                .andExpect(jsonPath("$.closePatch.command").value("mvn -q package"))
                .andExpect(jsonPath("$.closePatch.baseSha").doesNotExist());

        // ③ 병합(baseSha 기록). 패치를 올리기 전 PATCH_BUILT는 거절 — 변경 없음 표시도 없으니 파일이 있어야 한다
        merged(id);
        reportProgress(id, "{\"workerId\":\"w1\",\"step\":\"PATCH_BUILT\"}").andExpect(status().isBadRequest());

        // ④ 업로드(원시 본문 + sha256 헤더). 같은 해시 재전송은 본문을 버리고 같은 정보(재시도 멱등)
        byte[] zip = "PATCH-ZIP-BYTES-FOR-IT".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
        for (int attempt = 1; attempt <= 2; attempt++) {
            uploadPatch(id, sha, 3, zip)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(matchesPattern("\\d{8}_패치_흐름\\.zip")))
                    .andExpect(jsonPath("$.sizeBytes").value(zip.length))
                    .andExpect(jsonPath("$.sha256").value(sha))
                    .andExpect(jsonPath("$.fileCount").value(3));
        }
        Path stored = attachmentRoot.resolve("task-" + id).resolve("patch").resolve("patch.zip");
        assertThat(stored).exists();
        assertThat(Files.readAllBytes(stored)).isEqualTo(zip);
        mvc.perform(get("/worker/tasks/" + id + "/patch").header(WORKER_KEY, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value(sha));

        // ⑤ 남은 단계 → 작업종료
        progress(id, "PATCH_BUILT");
        progress(id, "UNDEPLOYED");
        progress(id, "NOTE_WRITTEN");
        progress(id, "BRANCH_DELETED");
        reportClosed(id).andExpect(status().isNoContent());

        // ⑥ 상세 — 패치 모드·이름·크기·파일 수, 이름은 yyyyMMdd_제목.zip
        String detail = mvc.perform(get("/api/tasks/" + id).with(user("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.close.patchMode").value("REQUIRED"))
                .andExpect(jsonPath("$.close.patch.name").value(matchesPattern("\\d{8}_패치_흐름\\.zip")))
                .andExpect(jsonPath("$.close.patch.sizeBytes").value(zip.length))
                .andExpect(jsonPath("$.close.patch.fileCount").value(3))
                .andExpect(jsonPath("$.close.patch.empty").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(detail).at("/close/patch/name").asText()).endsWith("_패치_흐름.zip");

        // ⑦ 내려받기 주소 — 로그인 필요, 다른 사용자 403, 등록자는 60초 1회용 주소
        mvc.perform(post("/api/tasks/" + id + "/patch/download-link")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tasks/" + id + "/patch/download-link").with(user("user2")))
                .andExpect(status().isForbidden());
        String body = mvc.perform(post("/api/tasks/" + id + "/patch/download-link").with(user("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSec").value(60))
                .andReturn().getResponse().getContentAsString();
        String url = mapper.readTree(body).get("url").asText();
        assertThat(url).startsWith("/api/artifact-downloads/");

        // 인증 없는 GET이 올린 바이트 그대로 주고, 같은 토큰 두 번째는 404 (이름은 RFC 5987 인코딩이라 한글을 그대로 비교하지 않는다)
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andExpect(header().string("Content-Disposition", containsString(".zip")))
                .andExpect(content().bytes(zip));
        mvc.perform(get(url)).andExpect(status().isNotFound());
    }

    @Test
    void 패치_단계_없이는_작업종료가_안_되고_패치_없이_마무리하면_종료된다() throws Exception {
        long nano = System.nanoTime();
        saveCatalog(nano);
        Long id = deployedTask(nano, "패치 건너뛰기");
        requestAndApprove(id);

        claim().andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.closePatch.command").value("mvn -q package"));
        merged(id);
        progress(id, "UNDEPLOYED");
        progress(id, "NOTE_WRITTEN");
        progress(id, "BRANCH_DELETED");

        // PATCH_BUILT가 빠졌으니 작업종료 보고는 거절 — 상태는 종료중 그대로
        reportClosed(id).andExpect(status().isBadRequest());
        assertThat(taskRepo.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.CLOSING);

        // 워커가 패치 단계 실패를 보고 → 종료실패. 일반 사용자는 건너뛸 수 없고 관리자는 건너뛸 수 있다
        reportResult(id, "{\"workerId\":\"w1\",\"status\":\"CLOSE_FAILED\",\"failureReason\":\"패치: 빌드 실패\"}")
                .andExpect(status().isNoContent());
        String skip = "/api/tasks/" + id + "/close/skip-patch";
        mvc.perform(post(skip).with(user("user1"))).andExpect(status().isForbidden());
        mvc.perform(post(skip).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSE_PENDING"))
                .andExpect(jsonPath("$.close.patchMode").value("SKIPPED"));

        // 재claim은 패치 지시 없이 — 남은 단계는 없으니 바로 작업종료
        claim().andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("CLOSE"))
                .andExpect(jsonPath("$.closePatch").doesNotExist());
        reportClosed(id).andExpect(status().isNoContent());

        mvc.perform(get("/api/tasks/" + id).with(user("user1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.close.patchMode").value("SKIPPED"))
                .andExpect(jsonPath("$.close.patch").doesNotExist());
        // 패치를 만든 적이 없으니 내려받기 주소는 404
        mvc.perform(post("/api/tasks/" + id + "/patch/download-link").with(user("user1")))
                .andExpect(status().isNotFound());
    }
}
