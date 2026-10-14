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
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 종료 산출물 흐름 통합 테스트 (스펙 2026-10-14). Testcontainers 기반 — RUN_TESTCONTAINERS=true 일 때만(CI).
 *
 * 승인(모드 REQUIRED) → claim의 closeArtifact → 업로드 → 진행 보고 → 작업종료 → 커밋 뒤 메일,
 * 1회용 내려받기(실제 보안 체인에서 권한·1회성·판정 순서), 산출물 없이 마무리 경로를 실제 DB로 돈다.
 * SMTP는 127.0.0.1:1(접속 거부)이라 메일은 FAILED가 되어야 하고, 그래도 작업종료는 유지되고 /actuator/health는 UP이어야 한다.
 *
 * 메일·산출물·첨부 루트 설정은 이 클래스의 컨텍스트에만 들어간다 — @TestPropertySource·@DynamicPropertySource는
 * 컨텍스트 캐시 키라 다른 통합 테스트 컨텍스트로 새지 않는다. 첨부 정리 cron은 TestcontainersConfig가 끈다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = TestcontainersConfig.class)
@TestPropertySource(properties = {
        "app.close.artifact.enabled=true",
        "app.public-base-url=https://app.test",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=1",
        "spring.mail.username=bot@hamonsoft.co.kr"
})
class CloseArtifactFlowIntegrationTest {

    private static final String WORKER_KEY = "X-Worker-API-Key";
    private static final String ARTIFACT_PATH = "/usr/local/tomcat/webapps/ROOT.war";

    /** 이 클래스 전용 첨부 루트 — 운영자 실제 ~/netis-maker/attachments를 건드리지 않는다. 컨텍스트를 만들 때만 생긴다. */
    private static Path attachmentRoot;

    @DynamicPropertySource
    static void attachmentDir(DynamicPropertyRegistry r) {
        try {
            attachmentRoot = Files.createTempDirectory("close-artifact-it").toAbsolutePath().normalize();
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

    private static RequestPostProcessor user(String id, String email) {
        return jwt().jwt(b -> {
                    b.claim("username", id).claim("authorities", List.of("ROLE_USER"));
                    if (email != null) b.claim("email", email);
                })
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(b -> b.claim("username", "admin").claim("authorities", List.of("ROLE_ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private ResultActions claim() throws Exception {
        return mvc.perform(get("/worker/next-task").param("workerId", "w1").header(WORKER_KEY, apiKey));
    }

    private ResultActions reportProgress(Long id, String step) throws Exception {
        return mvc.perform(post("/worker/tasks/" + id + "/close-progress").header(WORKER_KEY, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"w1\",\"step\":\"" + step + "\"}"));
    }

    private void progress(Long id, String step) throws Exception {
        reportProgress(id, step).andExpect(status().isNoContent());
    }

    private void reportClosed(Long id) throws Exception {
        mvc.perform(post("/worker/tasks/" + id + "/result").header(WORKER_KEY, apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"workerId\":\"w1\",\"status\":\"CLOSED\"}"))
                .andExpect(status().isNoContent());
    }

    private ResultActions upload(Long id, String name, String sha256, byte[] body) throws Exception {
        return mvc.perform(put("/worker/tasks/" + id + "/artifact").header(WORKER_KEY, apiKey)
                .param("workerId", "w1").param("name", name).param("source", "netis-task-" + id + ":0123456:" + ARTIFACT_PATH)
                .header("X-Artifact-Sha256", sha256)
                .contentType(MediaType.APPLICATION_OCTET_STREAM).content(body));
    }

    private static String linkPath(Long id) {
        return "/api/tasks/" + id + "/artifact/download-link";
    }

    private static String resendPath(Long id) {
        return "/api/tasks/" + id + "/close/mail/resend";
    }

    /** 1회용 내려받기 주소 발급 — 60초짜리 상대 경로. */
    private String issueLink(Long id, RequestPostProcessor who) throws Exception {
        String body = mvc.perform(post(linkPath(id)).with(who))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSec").value(60))
                .andReturn().getResponse().getContentAsString();
        String url = mapper.readTree(body).get("url").asText();
        assertThat(url).startsWith("/api/artifact-downloads/");
        return url;
    }

    /** 메일은 작업종료 커밋 뒤 close-mail 스레드가 비동기로 남긴다 — 상태가 기록될 때까지 기다린다(SMTP 타임아웃 10초보다 넉넉히). */
    private Task awaitMailRecorded(Long id) {
        return await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> taskRepo.findById(id).orElseThrow(), t -> t.getCloseMailStatus() != null);
    }

    @Test
    void 승인부터_업로드_1회용_내려받기_메일까지() throws Exception {
        long nano = System.nanoTime();
        RepoCatalogEntry cat = RepoCatalogEntry.create("산출물-it-" + nano, "https://github.com/owner/repo-" + nano + ".git",
                "github", "owner/repo-" + nano, null, null, "admin");
        cat.setArtifactEnabled(true);
        cat.setArtifactPath(ARTIFACT_PATH);
        catalogId = catalogRepo.save(cat).getId();

        Task t = Task.create("owner/repo-" + nano, "main", "산출물 흐름", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        t.setStatus(TaskStatus.DEPLOYED);
        t.setPrNumber(3);
        t.setPrUrl("https://github.com/owner/repo/pull/3");
        t.setHeadBranch("netismaker/task-x");
        t.setHeadSha("0123456789abcdef0123456789abcdef01234567");
        t.setRepoCatalogId(catalogId);
        Long id = taskRepo.save(t).getId();

        // ① 등록자 종료 요청(JWT email → 수신 주소) → 관리자 승인: 전역 스위치와 레포가 모두 켜져 산출물 모드 REQUIRED
        mvc.perform(post("/api/tasks/" + id + "/close-request").with(user("user1", "user1@hamonsoft.co.kr")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + id + "/close/approve").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSE_PENDING"))
                .andExpect(jsonPath("$.close.artifactMode").value("REQUIRED"));

        // ② 워커 claim — 산출물 지시(카탈로그의 이미지 안 경로)와 노트용 작업 주소
        claim().andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("CLOSE"))
                .andExpect(jsonPath("$.closeArtifact.path").value(ARTIFACT_PATH))
                .andExpect(jsonPath("$.closeContext.taskUrl").value("https://app.test/tasks/" + id));

        // ③ 병합·배포 중지. 파일을 올리기 전 '산출물 올림' 보고는 거절
        progress(id, "MERGED");
        progress(id, "UNDEPLOYED");
        reportProgress(id, "ARTIFACT_UPLOADED").andExpect(status().isBadRequest());

        // ④ 업로드(원시 본문 + sha256 헤더). 같은 이름·해시 재전송은 본문을 버리고 같은 정보(재시도 멱등)
        byte[] war = "WAR-BYTES-FOR-IT".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(war));
        String name = "repo-" + nano + "-task-" + id + "-0123456.war";
        for (int attempt = 1; attempt <= 2; attempt++) {
            upload(id, name, sha, war)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(name))
                    .andExpect(jsonPath("$.sizeBytes").value(war.length))
                    .andExpect(jsonPath("$.sha256").value(sha));
        }
        Path stored = attachmentRoot.resolve("task-" + id).resolve("artifact").resolve(name);
        assertThat(stored).exists();
        assertThat(Files.readAllBytes(stored)).isEqualTo(war);
        mvc.perform(get("/worker/tasks/" + id + "/artifact").header(WORKER_KEY, apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));

        // ⑤ 남은 단계 → 작업종료
        progress(id, "ARTIFACT_UPLOADED");
        progress(id, "NOTE_WRITTEN");
        progress(id, "BRANCH_DELETED");
        reportClosed(id);

        // ⑥ 내려받기 주소 — 로그인 필요, 판정 순서(작업 없음 404 → 다른 사용자 403), 등록자·관리자만
        mvc.perform(post(linkPath(id))).andExpect(status().isUnauthorized());
        mvc.perform(post(linkPath(id + 1_000_000)).with(user("user1", null))).andExpect(status().isNotFound());
        mvc.perform(post(linkPath(id)).with(user("user2", null))).andExpect(status().isForbidden());
        String url = issueLink(id, user("user1", null));

        // 인증 없는 GET이 실제 보안 체인을 통과해 파일을 준다. 같은 토큰 두 번째는 404, 모르는 토큰도 404
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(name)))
                .andExpect(content().bytes(war));
        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get("/api/artifact-downloads/nope")).andExpect(status().isNotFound());

        // 인증 없이 열리는 것은 GET뿐 — POST는 보안 체인에서 막혀 토큰을 쓰지 않는다(관리자 주소가 그대로 살아 있다)
        String adminUrl = issueLink(id, admin());
        mvc.perform(post(adminUrl)).andExpect(status().isUnauthorized());
        mvc.perform(get(adminUrl)).andExpect(status().isOk()).andExpect(content().bytes(war));

        // ⑦ 메일 — 접속 거부라 FAILED. 작업종료는 그대로이고 상태 이력에 실패가 남는다(상태와 같은 트랜잭션)
        Task done = awaitMailRecorded(id);
        assertThat(done.getStatus()).isEqualTo(TaskStatus.CLOSED);
        assertThat(done.getCloseMailStatus()).isEqualTo("FAILED");
        assertThat(done.getCloseMailAt()).isNotNull();
        assertThat(done.getRequesterEmail()).isEqualTo("user1@hamonsoft.co.kr");
        assertThat(historyRepo.findByTaskIdOrderByAtDescIdDesc(id, Limit.of(50)))
                .anyMatch(h -> "mail".equals(h.getActorId()) && h.getReason() != null
                        && h.getReason().startsWith("메일 실패:"));
        mvc.perform(get("/api/tasks/" + id).with(user("user1", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.close.artifact.name").value(name))
                .andExpect(jsonPath("$.close.mail.status").value("FAILED"));
        // 메일 헬스 체크가 꺼져 있어(management.health.mail.enabled=false) 닿지 않는 SMTP가 health를 DOWN으로 만들지 않는다
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // ⑧ 다시 보내기 — 관리자 전용, 동기라 결과를 바로 돌려준다
        mvc.perform(post(resendPath(id)).with(user("user1", null))).andExpect(status().isForbidden());
        mvc.perform(post(resendPath(id)).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // ⑨ 보관 파일이 정리됐으면 주소 발급은 410
        Files.delete(stored);
        mvc.perform(post(linkPath(id)).with(user("user1", null))).andExpect(status().isGone());
    }

    @Test
    void 산출물_단계에서_멈춘_종료는_산출물_없이_마무리해_4단계로_작업종료된다() throws Exception {
        Task t = Task.create("owner/repo", "main", "건너뛰기", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        t.setStatus(TaskStatus.CLOSE_FAILED);
        t.setCloseArtifactMode("REQUIRED");
        t.setCloseProgress("MERGED,UNDEPLOYED");
        t.setFailureReason("산출물: 이미지 없음");
        Long id = taskRepo.save(t).getId();

        String skip = "/api/tasks/" + id + "/close/skip-artifact";
        // 바디 없는 요청(화면은 사유를 보내지 않는다)도 인자 해석을 통과해 권한 검사까지 간다 — 일반 사용자는 403
        mvc.perform(post(skip).with(user("user1", null)))
                .andExpect(status().isForbidden());
        mvc.perform(post(skip).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());   // 사유 500자 상한
        mvc.perform(post(skip).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"이미지 수동 삭제\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSE_PENDING"))
                .andExpect(jsonPath("$.close.artifactMode").value("SKIPPED"));

        // 재claim은 산출물 지시 없이 남은 단계부터 — 노트에는 '산출물 없음'을 쓴다
        claim().andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.kind").value("CLOSE"))
                .andExpect(jsonPath("$.closeProgress").value("MERGED,UNDEPLOYED"))
                .andExpect(jsonPath("$.closeArtifact").doesNotExist())
                .andExpect(jsonPath("$.closeContext.artifactSkipped").value(true));
        progress(id, "NOTE_WRITTEN");
        progress(id, "BRANCH_DELETED");
        reportClosed(id);   // SKIPPED면 산출물 단계 없이 4단계로 받는다

        // 보관한 산출물이 없으니 내려받기 주소는 404, 메일 다시 보내기는 대상이 아니라 409
        mvc.perform(post(linkPath(id)).with(user("user1", null))).andExpect(status().isNotFound());
        mvc.perform(post(resendPath(id)).with(admin())).andExpect(status().isConflict());
    }
}
