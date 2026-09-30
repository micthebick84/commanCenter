package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 임시 디렉터리 + 목 리포지토리로 스캔 → 판정 → 삭제 전체 흐름을 검증한다.
 * 판정 조합 자체는 AttachmentCleanupPlannerTest가, 실제 쿼리는 AttachmentCleanupJobIntegrationTest가 맡는다.
 */
class AttachmentCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-09-30T03:30:00Z");

    @TempDir Path tmp;
    private Path root;

    private final TaskRepository taskRepo = mock(TaskRepository.class);
    private final InterviewSessionRepository sessionRepo = mock(InterviewSessionRepository.class);
    private final List<TaskState> taskRows = new ArrayList<>();
    private final List<SessionState> sessionRows = new ArrayList<>();

    record TaskState(Long id, OffsetDateTime deletedAt) implements TaskRepository.AttachmentOwnerState {
        @Override public Long getId() { return id; }
        @Override public OffsetDateTime getDeletedAt() { return deletedAt; }
    }

    record SessionState(Long id, InterviewKind kind, InterviewStatus status, OffsetDateTime lastActivityAt)
            implements InterviewSessionRepository.AttachmentOwnerState {
        @Override public Long getId() { return id; }
        @Override public InterviewKind getKind() { return kind; }
        @Override public InterviewStatus getStatus() { return status; }
        @Override public OffsetDateTime getLastActivityAt() { return lastActivityAt; }
    }

    @BeforeEach
    void setUp() {
        root = tmp.resolve("attachments");
        when(taskRepo.findAttachmentOwnerStates(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return taskRows.stream().filter(r -> ids.contains(r.id())).toList();
        });
        when(sessionRepo.findAttachmentOwnerStates(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return sessionRows.stream().filter(r -> ids.contains(r.id())).toList();
        });
        when(taskRepo.findMaxId()).thenReturn(100L);
        when(sessionRepo.findMaxId()).thenReturn(100L);
    }

    private AttachmentCleanupJob job(boolean enabled) {
        return new AttachmentCleanupJob(new AttachmentStorage(root.toString(), 10, 20, 50),
                taskRepo, sessionRepo, enabled, 30, 90, 24);
    }

    private static OffsetDateTime daysAgo(long days) {
        return OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(days)), ZoneOffset.UTC);
    }

    /** 파일을 채운 뒤 디렉터리 mtime을 지정 — 하위 쓰기가 mtime을 갱신하므로 마지막에 설정한다. */
    private Path ownerDir(String name, Instant mtime, String... files) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        for (String f : files) {
            Path p = dir.resolve(f);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "0123456789");   // 10바이트
        }
        Files.setLastModifiedTime(dir, FileTime.from(mtime));
        return dir;
    }

    @Test
    void 정책대로_지우고_나머지는_남기며_요약을_돌려준다() throws Exception {
        Instant old = NOW.minus(Duration.ofDays(400));
        Path active = ownerDir("task-1", old, "1-a.txt");
        taskRows.add(new TaskState(1L, null));
        Path deletedExpired = ownerDir("task-2", old, "1-a.txt", "2-b.txt");
        taskRows.add(new TaskState(2L, daysAgo(40)));
        Path deletedRecent = ownerDir("task-3", old, "1-a.txt");
        taskRows.add(new TaskState(3L, daysAgo(10)));
        Path orphanOld = ownerDir("task-4", NOW.minus(Duration.ofDays(2)));             // 롤백 잔재(빈 디렉터리)
        Path orphanFresh = ownerDir("task-5", NOW.minus(Duration.ofHours(1)), "1-a.txt"); // 등록 진행 중일 수 있음
        Path orphanUnallocated = ownerDir("task-500", old, "1-a.txt");                   // max id(100) 초과

        Path closedExpired = ownerDir("question-10", old, "create/1-설계.docx", "create/1-설계.docx.txt", "3/1-로그.txt");
        sessionRows.add(new SessionState(10L, InterviewKind.QUESTION, InterviewStatus.CANCELLED, daysAgo(100)));
        Path awaiting = ownerDir("question-11", old, "create/1-a.txt");
        sessionRows.add(new SessionState(11L, InterviewKind.QUESTION, InterviewStatus.AWAITING_INPUT, daysAgo(200)));
        Path closedRecent = ownerDir("question-12", old, "create/1-a.txt");
        sessionRows.add(new SessionState(12L, InterviewKind.QUESTION, InterviewStatus.EXPIRED, daysAgo(30)));
        Path questionOrphan = ownerDir("question-13", NOW.minus(Duration.ofDays(3)), "create/1-a.txt");

        Files.createDirectories(root.resolve("task-007"));   // 정규형 아님
        Files.writeString(root.resolve("notes.txt"), "x");   // 일반 파일

        AttachmentCleanupJob.Summary s = job(true).runOnce(NOW);

        assertThat(deletedExpired).doesNotExist();
        assertThat(orphanOld).doesNotExist();
        assertThat(closedExpired).doesNotExist();
        assertThat(questionOrphan).doesNotExist();
        assertThat(List.of(active, deletedRecent, orphanFresh, orphanUnallocated, awaiting, closedRecent))
                .allSatisfy(p -> assertThat(p.resolve(firstFile(p))).exists());
        assertThat(root.resolve("task-007")).isDirectory();
        assertThat(root.resolve("notes.txt")).exists();

        assertThat(s.deletedTask()).isEqualTo(1);
        assertThat(s.deletedQuestion()).isEqualTo(1);
        assertThat(s.deletedOrphan()).isEqualTo(2);
        assertThat(s.deleted()).isEqualTo(4);
        // task-2: 2파일 + question-10: 3파일 + question-13: 1파일, 각 10바이트
        assertThat(s.freedBytes()).isEqualTo(60);
        assertThat(s.kept()).isEqualTo(6);
        assertThat(s.skipped()).isEqualTo(2);
        assertThat(s.failed()).isZero();
    }

    private static String firstFile(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return dir.relativize(walk.filter(Files::isRegularFile).findFirst().orElseThrow()).toString();
        }
    }

    @Test
    void 심볼릭_링크는_따라가지_않는다() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "keep me");

        // (1) 이름이 정규형인 링크 자체 — 대상 판정에서 빠진다(건너뜀)
        Files.createDirectories(root);
        Files.createSymbolicLink(root.resolve("task-6"), outside);
        taskRows.add(new TaskState(6L, daysAgo(365)));
        // (2) 삭제 대상 디렉터리 안의 링크 — 링크만 지워지고 대상은 남는다
        Path doomed = ownerDir("task-2", NOW.minus(Duration.ofDays(400)), "1-a.txt");
        Files.createSymbolicLink(doomed.resolve("2-link"), outside);
        Files.createSymbolicLink(doomed.resolve("3-file-link"), precious);
        Files.setLastModifiedTime(doomed, FileTime.from(NOW.minus(Duration.ofDays(400))));
        taskRows.add(new TaskState(2L, daysAgo(40)));

        AttachmentCleanupJob.Summary s = job(true).runOnce(NOW);

        assertThat(doomed).doesNotExist();
        assertThat(Files.isSymbolicLink(root.resolve("task-6"))).isTrue();
        assertThat(precious).hasContent("keep me");
        assertThat(s.deletedTask()).isEqualTo(1);
        assertThat(s.freedBytes()).isEqualTo(10);   // 링크 크기는 해제 용량에 넣지 않는다
        assertThat(s.skipped()).isEqualTo(1);
    }

    @Test
    void DB_조회가_실패하면_아무것도_지우지_않는다() throws Exception {
        Path doomed = ownerDir("task-2", NOW.minus(Duration.ofDays(400)), "1-a.txt");
        when(taskRepo.findAttachmentOwnerStates(any())).thenThrow(new IllegalStateException("DB down"));

        assertThatThrownBy(() -> job(true).runOnce(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(doomed.resolve("1-a.txt")).exists();
        // 스케줄 진입점은 예외를 삼키고 다음 주기를 기다린다
        job(true).cleanup();
        assertThat(doomed.resolve("1-a.txt")).exists();
    }

    @Test
    void 비활성화면_스캔도_조회도_하지_않는다() throws Exception {
        Path doomed = ownerDir("task-2", NOW.minus(Duration.ofDays(400)), "1-a.txt");
        taskRows.add(new TaskState(2L, daysAgo(400)));

        job(false).cleanup();

        assertThat(doomed.resolve("1-a.txt")).exists();
        verifyNoInteractions(taskRepo, sessionRepo);
    }

    @Test
    void 루트가_없으면_조회_없이_빈_요약() throws Exception {
        AttachmentCleanupJob.Summary s = job(true).runOnce(NOW);
        assertThat(s.deleted()).isZero();
        assertThat(s.kept()).isZero();
        verifyNoInteractions(taskRepo, sessionRepo);
    }

    @Test
    void 한_종류만_있으면_다른_종류는_조회하지_않는다() throws Exception {
        ownerDir("task-1", NOW, "1-a.txt");
        taskRows.add(new TaskState(1L, null));

        job(true).runOnce(NOW);

        verify(sessionRepo, never()).findAttachmentOwnerStates(any());
        verify(sessionRepo, never()).findMaxId();
    }

    @Test
    void id_조회는_청크_단위로_나눈다() throws Exception {
        int n = AttachmentCleanupJob.LOOKUP_CHUNK + 1;
        for (long id = 1; id <= n; id++) {
            Files.createDirectories(root.resolve("task-" + id));
            taskRows.add(new TaskState(id, null));
        }

        AttachmentCleanupJob.Summary s = job(true).runOnce(NOW);

        verify(taskRepo, times(2)).findAttachmentOwnerStates(any());
        assertThat(s.kept()).isEqualTo(n);
        assertThat(s.deleted()).isZero();
    }

    @Test
    void 설정값_검증() {
        AttachmentStorage storage = new AttachmentStorage(root.toString(), 10, 20, 50);
        assertThatThrownBy(() -> new AttachmentCleanupJob(storage, taskRepo, sessionRepo, true, -1, 90, 24))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttachmentCleanupJob(storage, taskRepo, sessionRepo, true, 30, -1, 24))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttachmentCleanupJob(storage, taskRepo, sessionRepo, true, 30, 90, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 사람이_읽는_용량_표기() {
        assertThat(AttachmentCleanupJob.humanBytes(512)).isEqualTo("512B");
        assertThat(AttachmentCleanupJob.humanBytes(1536)).isEqualTo("1.5KB");
        assertThat(AttachmentCleanupJob.humanBytes(50L * 1024 * 1024)).isEqualTo("50.0MB");
        assertThat(AttachmentCleanupJob.humanBytes(3L * 1024 * 1024 * 1024)).isEqualTo("3.00GB");
    }
}
