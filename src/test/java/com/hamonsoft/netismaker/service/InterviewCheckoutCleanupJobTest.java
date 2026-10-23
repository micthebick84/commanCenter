package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.InterviewKind;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import com.hamonsoft.netismaker.repository.InterviewSessionRepository;
import com.hamonsoft.netismaker.service.InterviewCheckoutCleanupJob.Summary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 임시 루트(@TempDir) + 목 리포지토리로 스캔 → 판정 → 삭제 전체 흐름을 검증한다. 실제 체크아웃 루트는 건드리지 않는다.
 * 판정 조합은 InterviewCheckoutCleanupPlannerTest가, 트리 삭제 자체는 FileTreesTest가 맡는다.
 */
class InterviewCheckoutCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:50:00Z");
    /** 폴더 mtime — 판정에 쓰이는 건 행이 없는 고아뿐이다. */
    private static final Instant OLD = NOW.minus(Duration.ofDays(400));

    @TempDir Path tmp;
    private Path root;

    private final InterviewSessionRepository sessionRepo = mock(InterviewSessionRepository.class);
    private final List<SessionState> rows = new ArrayList<>();

    record SessionState(Long id, InterviewKind kind, InterviewStatus status, OffsetDateTime lastActivityAt)
            implements InterviewSessionRepository.AttachmentOwnerState {
        @Override public Long getId() { return id; }
        @Override public InterviewKind getKind() { return kind; }
        @Override public InterviewStatus getStatus() { return status; }
        @Override public OffsetDateTime getLastActivityAt() { return lastActivityAt; }
    }

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(tmp.resolve("interviews"));
        when(sessionRepo.findAttachmentOwnerStates(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return rows.stream().filter(r -> ids.contains(r.id())).toList();
        });
        when(sessionRepo.findMaxId()).thenReturn(100L);
    }

    private InterviewCheckoutCleanupJob job() {
        return job(true);
    }

    private InterviewCheckoutCleanupJob job(boolean enabled) {
        return new InterviewCheckoutCleanupJob(new InterviewWorkPaths(root.toString()), sessionRepo, enabled, 7);
    }

    private static OffsetDateTime daysAgo(long days) {
        return OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(days)), ZoneOffset.UTC);
    }

    private void session(long id, InterviewStatus status, long lastActivityDaysAgo) {
        rows.add(new SessionState(id, InterviewKind.QUESTION, status, daysAgo(lastActivityDaysAgo)));
    }

    /** 폴더와 10바이트짜리 파일들을 만든 뒤 폴더 mtime을 지정한다 — 하위 쓰기가 mtime을 갱신하므로 마지막에 설정한다. */
    private Path folder(String relative, Instant mtime, String... files) throws IOException {
        Path dir = Files.createDirectories(root.resolve(relative));
        for (String f : files) {
            Path p = dir.resolve(f);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "0123456789");
        }
        Files.setLastModifiedTime(dir, FileTime.from(mtime));
        return dir;
    }

    // ── 링크 도우미: Windows는 정션(권한 불필요), 그 밖은 심볼릭 링크. 못 만들면 테스트를 건너뛴다 ──

    /** mklink /J는 관리자 권한 없이 만들 수 있다. 실패하면(정책 등) 테스트를 건너뛴다. */
    private static void junction(Path link, Path target) throws Exception {
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        boolean done = p.waitFor(30, TimeUnit.SECONDS);
        assumeTrue(done && p.exitValue() == 0, "mklink /J 실패 — 정션을 만들 수 없는 환경");
        BasicFileAttributes a = Files.readAttributes(link, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assumeTrue(a.isOther() && !Files.isSymbolicLink(link), "정션이 isOther로 보이지 않는 환경");
    }

    private static void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "심볼릭 링크를 만들 수 없는 환경: " + e);
        }
    }

    private static void dirLink(Path link, Path target) throws Exception {
        if (OS.WINDOWS.isCurrentOs()) junction(link, target);
        else symlink(link, target);
    }

    private static boolean existsNoFollow(Path p) {
        return Files.exists(p, LinkOption.NOFOLLOW_LINKS);
    }

    // ── 기본 동작 ──────────────────────────────────────────

    @Test
    void deletes_target_and_refs_of_an_old_closed_session() throws Exception {
        Path target = folder("_gitlab/g+p/session-5", OLD, "x.txt");
        Path refs = folder("_refs/5", OLD, "12/y.txt");
        session(5, InterviewStatus.CANCELLED, 10);
        when(sessionRepo.findMaxId()).thenReturn(9L);

        Summary s = job().runOnce(NOW);

        assertThat(target).doesNotExist();
        assertThat(refs).doesNotExist();
        assertThat(root.resolve("_refs")).isDirectory();         // _refs 자체는 남는다
        assertThat(root.resolve("_gitlab/g+p")).isDirectory();   // 비어 버린 상위 폴더도 지우지 않는다
        assertThat(s).isEqualTo(new Summary(2, 20, 0, 0));       // 폴더 2개(대상 + 참조), 10바이트 파일 2개
    }

    @Test
    void keeps_folders_of_open_sessions() throws Exception {
        Path target = folder("owner/repo/session-5", OLD, "x.txt");
        Path refs = folder("_refs/5", OLD, "12/y.txt");
        session(5, InterviewStatus.AWAITING_INPUT, 30);

        Summary s = job().runOnce(NOW);

        assertThat(target.resolve("x.txt")).exists();
        assertThat(refs.resolve("12/y.txt")).exists();
        assertThat(s).isEqualTo(new Summary(0, 0, 2, 0));
    }

    @Test
    void db_failure_deletes_nothing() throws Exception {
        Path target = folder("owner/repo/session-5", OLD, "x.txt");
        Path refs = folder("_refs/5", OLD, "12/y.txt");
        session(5, InterviewStatus.CANCELLED, 100);
        // doThrow: when(...)으로 다시 스텁하면 setUp의 응답 람다가 null 인자로 한 번 불린다
        doThrow(new IllegalStateException("DB down")).when(sessionRepo).findAttachmentOwnerStates(any());

        assertThatThrownBy(() -> job().runOnce(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(target.resolve("x.txt")).exists();
        assertThat(refs.resolve("12/y.txt")).exists();

        // 스케줄 진입점은 예외를 삼키고 그 회차를 건너뛴다 — 다음 회차에 다시 시도한다
        job().cleanup();
        assertThat(target.resolve("x.txt")).exists();
        assertThat(refs.resolve("12/y.txt")).exists();
    }

    @Test
    void max_id_lookup_failure_deletes_nothing() throws Exception {
        Path target = folder("owner/repo/session-5", OLD, "x.txt");
        session(5, InterviewStatus.CANCELLED, 100);
        when(sessionRepo.findMaxId()).thenThrow(new IllegalStateException("DB down"));

        assertThatThrownBy(() -> job().runOnce(NOW)).isInstanceOf(IllegalStateException.class);
        job().cleanup();

        assertThat(target.resolve("x.txt")).exists();
    }

    @Test
    void rejects_retention_below_one() {
        InterviewWorkPaths paths = new InterviewWorkPaths(root.toString());
        assertThatThrownBy(() -> new InterviewCheckoutCleanupJob(paths, sessionRepo, true, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InterviewCheckoutCleanupJob(paths, sessionRepo, true, -1))
                .isInstanceOf(IllegalArgumentException.class);
        // 꺼 둔 상태여도 값 검증은 한다 — 잘못된 설정이 켜는 순간에야 드러나면 안 된다
        assertThatThrownBy(() -> new InterviewCheckoutCleanupJob(paths, sessionRepo, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new InterviewCheckoutCleanupJob(paths, sessionRepo, true, 1)).isNotNull();
    }

    @Test
    void disabled_job_touches_nothing() throws Exception {
        Path target = folder("owner/repo/session-5", OLD, "x.txt");
        session(5, InterviewStatus.CANCELLED, 100);

        job(false).cleanup();

        assertThat(target.resolve("x.txt")).exists();
        verifyNoInteractions(sessionRepo);
    }

    @Test
    void empty_root_makes_no_db_calls() throws Exception {
        assertThat(job().runOnce(NOW)).isEqualTo(new Summary(0, 0, 0, 0));
        verifyNoInteractions(sessionRepo);
    }

    @Test
    void a_second_run_finds_nothing_left() throws Exception {
        folder("owner/repo/session-5", OLD, "x.txt");
        folder("_refs/5", OLD, "12/y.txt");
        session(5, InterviewStatus.FAILED, 10);

        assertThat(job().runOnce(NOW).deleted()).isEqualTo(2);
        assertThat(job().runOnce(NOW)).isEqualTo(new Summary(0, 0, 0, 0));
    }

    @Test
    void refs_without_a_target_folder_are_judged_by_the_same_session_row() throws Exception {
        // 대상 폴더는 이미 없고 참조 폴더만 남은 경우(대상만 먼저 지워졌거나 참조만 받은 세션)
        Path closedRefs = folder("_refs/7", OLD, "3/a.txt");
        Path openRefs = folder("_refs/8", OLD, "3/a.txt");
        session(7, InterviewStatus.EXPIRED, 20);
        session(8, InterviewStatus.RUNNING, 20);

        Summary s = job().runOnce(NOW);

        assertThat(closedRefs).doesNotExist();
        assertThat(openRefs.resolve("3/a.txt")).exists();
        assertThat(s).isEqualTo(new Summary(1, 10, 1, 0));
    }

    @Test
    void applies_the_policy_to_a_mix_of_folders() throws Exception {
        // 지운다: 끝난 세션의 대상 + 참조, 오래된 고아(방어용 `_/` 첫 단계)
        Path closedTarget = folder("owner/repo/session-1", OLD, "a.txt");
        Path closedRefs = folder("_refs/1", OLD, "12/a.txt");
        session(1, InterviewStatus.CANCELLED, 10);
        Path orphanOld = folder("_/solo/session-50", NOW.minus(Duration.ofDays(8)), "a.txt");
        // 남긴다: 보존 기간 이내, 진행 중, 최근 고아, 미할당 id(최대 id 100 초과)
        Path closedRecent = folder("owner/repo/session-2", OLD, "a.txt");
        session(2, InterviewStatus.EXPIRED, 3);
        Path open = folder("_gitlab/g+p/session-3", OLD, "a.txt");
        Path openRefs = folder("_refs/3", OLD, "12/a.txt");
        session(3, InterviewStatus.RUNNING, 30);
        Path orphanRecent = folder("owner/repo/session-51", NOW.minus(Duration.ofDays(1)), "a.txt");
        Path unallocated = folder("owner/repo/session-500", OLD, "a.txt");
        // 앱이 만든 이름이 아니거나 자리가 틀린 것은 후보도 아니다 — 닫힌 행(9)이 있어도 건드리지 않는다
        session(9, InterviewStatus.CANCELLED, 100);
        List<Path> notCandidates = List.of(
                folder("owner/repo/session-007", OLD, "a.txt"),
                folder("owner/repo/session-1x", OLD, "a.txt"),
                folder("owner/repo/session-0", OLD, "a.txt"),
                folder("session-9", OLD, "a.txt"),                       // 루트 바로 아래(1단계)
                folder("owner/session-9", OLD, "a.txt"),                 // 2단계
                folder("owner/repo/sub/session-9", OLD, "a.txt"),        // 4단계
                folder("_refs/stray/session-9", OLD, "a.txt"),           // 참조 루트 안은 대상 스캔에서 제외
                folder("_refs/abc", OLD, "a.txt"),
                folder("_refs/007", OLD, "a.txt"));
        Files.writeString(root.resolve("loose.txt"), "x");
        Files.writeString(root.resolve("owner/repo/notes.txt"), "x");
        Files.writeString(root.resolve("_refs/readme.txt"), "x");

        Summary s = job().runOnce(NOW);

        assertThat(List.of(closedTarget, closedRefs, orphanOld)).allSatisfy(p -> assertThat(p).doesNotExist());
        assertThat(List.of(closedRecent.resolve("a.txt"), open.resolve("a.txt"), openRefs.resolve("12/a.txt"),
                orphanRecent.resolve("a.txt"), unallocated.resolve("a.txt")))
                .allSatisfy(p -> assertThat(p).exists());
        assertThat(notCandidates).allSatisfy(p -> assertThat(p.resolve("a.txt")).exists());
        assertThat(root.resolve("loose.txt")).exists();
        assertThat(root.resolve("owner/repo/notes.txt")).exists();
        assertThat(root.resolve("_refs/readme.txt")).exists();
        assertThat(s).isEqualTo(new Summary(3, 30, 5, 0));
    }

    @Test
    void looks_sessions_up_in_chunks() throws Exception {
        int n = InterviewCheckoutCleanupJob.LOOKUP_CHUNK + 1;
        for (long id = 1; id <= n; id++) {
            Files.createDirectories(root.resolve("_refs/" + id));
            session(id, InterviewStatus.AWAITING_INPUT, 1);
        }

        Summary s = job().runOnce(NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(sessionRepo, times(2)).findAttachmentOwnerStates(captor.capture());
        assertThat(captor.getAllValues()).extracting(Collection::size)
                .containsExactlyInAnyOrder(InterviewCheckoutCleanupJob.LOOKUP_CHUNK, 1);
        verify(sessionRepo, times(1)).findMaxId();
        assertThat(s).isEqualTo(new Summary(0, 0, n, 0));
    }

    @Test
    void target_and_refs_of_one_session_are_looked_up_once() throws Exception {
        folder("owner/repo/session-5", OLD, "a.txt");
        folder("_refs/5", OLD, "12/a.txt");
        session(5, InterviewStatus.REGISTERED, 10);

        job().runOnce(NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(sessionRepo).findAttachmentOwnerStates(captor.capture());
        assertThat(captor.getValue()).containsExactly(5L);
    }

    @Test
    void no_candidates_means_no_db_calls() throws Exception {
        folder("owner/repo/session-007", OLD, "a.txt");
        folder("_refs/abc", OLD, "a.txt");
        Files.writeString(root.resolve("loose.txt"), "x");

        assertThat(job().runOnce(NOW)).isEqualTo(new Summary(0, 0, 0, 0));

        verify(sessionRepo, never()).findAttachmentOwnerStates(any());
        verify(sessionRepo, never()).findMaxId();
    }

    // ── 루트 방어 (판정 6): 쓸 수 없는 루트면 아무것도 지우지 않고 빈 요약 ──

    @Test
    void refuses_a_relative_root_and_deletes_nothing() throws Exception {
        Path doomed = folder("owner/repo/session-5", OLD, "x.txt");
        session(5, InterviewStatus.CANCELLED, 100);
        Path relative;
        try {
            relative = Path.of("").toAbsolutePath().relativize(root);   // 지금 작업 디렉터리 기준으로 같은 폴더를 가리키는 상대 경로
        } catch (IllegalArgumentException e) {
            assumeTrue(false, "작업 디렉터리와 임시 폴더가 다른 드라이브라 상대 경로를 만들 수 없는 환경");
            return;
        }
        assertThat(relative.isAbsolute()).isFalse();
        assertThat(relative.toAbsolutePath().normalize()).isEqualTo(root.toAbsolutePath().normalize());
        InterviewCheckoutCleanupJob relativeJob =
                new InterviewCheckoutCleanupJob(new InterviewWorkPaths(relative.toString()), sessionRepo, true, 7);

        assertThat(relativeJob.runOnce(NOW)).isEqualTo(new Summary(0, 0, 0, 0));
        relativeJob.cleanup();

        assertThat(doomed.resolve("x.txt")).exists();
        verifyNoInteractions(sessionRepo);
    }

    @Test
    void refuses_a_root_that_does_not_exist_or_is_not_a_directory() throws Exception {
        Path file = Files.writeString(tmp.resolve("not-a-dir"), "x");
        for (Path bad : List.of(tmp.resolve("missing"), file)) {
            InterviewCheckoutCleanupJob j =
                    new InterviewCheckoutCleanupJob(new InterviewWorkPaths(bad.toString()), sessionRepo, true, 7);

            assertThat(j.runOnce(NOW)).isEqualTo(new Summary(0, 0, 0, 0));
            j.cleanup();   // 예외 없이 끝난다
        }
        assertThat(file).hasContent("x");
        verifyNoInteractions(sessionRepo);
    }

    @Test
    void root_problem_names_every_unusable_root() {
        assertThat(InterviewCheckoutCleanupJob.rootProblem(root)).isNull();
        assertThat(InterviewCheckoutCleanupJob.rootProblem(Path.of("relative/interviews"))).isNotBlank();
        assertThat(InterviewCheckoutCleanupJob.rootProblem(tmp.resolve("missing"))).isNotBlank();
        // 파일시스템 루트·홈 디렉터리를 체크아웃 루트로 잘못 지정해도 스캔하지 않는다
        assertThat(InterviewCheckoutCleanupJob.rootProblem(tmp.getRoot())).isNotBlank();
        assertThat(InterviewCheckoutCleanupJob.rootProblem(Path.of(System.getProperty("user.home")))).isNotBlank();
    }

    // ── 삭제 직전 방어: 스캔한 그 폴더가 아직 루트 안의 일반 폴더인가 ──

    @Test
    void containment_check_accepts_only_plain_folders_that_really_live_under_the_root() throws Exception {
        Path realRoot = root.toRealPath();
        Path inside = Files.createDirectories(realRoot.resolve("owner/repo/session-5"));
        Path outside = Files.createDirectories(tmp.resolve("outside/session-5"));

        assertThat(InterviewCheckoutCleanupJob.isInsideRoot(realRoot, inside)).isTrue();
        assertThat(InterviewCheckoutCleanupJob.isInsideRoot(realRoot, realRoot)).isFalse();   // 루트 자신은 지우지 않는다
        assertThat(InterviewCheckoutCleanupJob.isInsideRoot(realRoot, outside)).isFalse();    // 루트 밖
        assertThat(InterviewCheckoutCleanupJob.isInsideRoot(realRoot, realRoot.resolve("owner/missing"))).isFalse();
        assertThat(InterviewCheckoutCleanupJob.isInsideRoot(realRoot, realRoot.resolve("owner/repo/../repo/session-5")))
                .isFalse();   // 정규형이 아닌 경로는 스캔이 만든 경로가 아니다
        assertThat(InterviewCheckoutCleanupJob.isInsideRoot(realRoot, realRoot.resolve("../outside/session-5")))
                .isFalse();   // ..로 루트를 벗어난다
        Files.writeString(realRoot.resolve("owner/file.txt"), "x");
        assertThat(InterviewCheckoutCleanupJob.isInsideRoot(realRoot, realRoot.resolve("owner/file.txt"))).isFalse();
    }

    @Test
    void containment_check_rejects_links() throws Exception {
        Path realRoot = root.toRealPath();
        Path outside = Files.createDirectories(tmp.resolve("outside/real/session-5"));
        Files.createDirectories(realRoot.resolve("owner"));
        Path link = realRoot.resolve("owner/link");
        dirLink(link, outside.getParent());
        try {
            assertThat(InterviewCheckoutCleanupJob.isInsideRoot(realRoot, link)).isFalse();                       // 링크 자체
            assertThat(InterviewCheckoutCleanupJob.isInsideRoot(realRoot, link.resolve("session-5"))).isFalse();  // 링크를 지나는 경로
        } finally {
            Files.deleteIfExists(link);
        }
    }

    // ── 링크·정션은 따라가지 않는다 ────────────────────────

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void does_not_follow_a_junction_out_of_the_root() throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path keep = Files.writeString(outside.resolve("keep.txt"), "지우면 안 됨");
        Path refs5 = Files.createDirectories(root.resolve("_refs/5"));
        Path link = refs5.resolve("12");
        junction(link, outside);
        Files.setLastModifiedTime(refs5, FileTime.from(OLD));
        session(5, InterviewStatus.CANCELLED, 10);
        try {
            Summary s = job().runOnce(NOW);

            assertThat(refs5).doesNotExist();   // 정션만 지워지고 폴더는 사라진다
            assertThat(keep).hasContent("지우면 안 됨");
            assertThat(s.deleted()).isEqualTo(1);
            assertThat(s.failed()).isZero();
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void unlinks_a_link_inside_a_deleted_folder_without_touching_its_target() throws Exception {
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside.resolve("sub"));
        Path keep = Files.writeString(outside.resolve("keep.txt"), "지우면 안 됨");
        Files.writeString(outside.resolve("sub/keep2.txt"), "지우면 안 됨 2");
        Path target = folder("owner/repo/session-5", OLD, "x.txt");
        Path refs = folder("_refs/5", OLD, "12/y.txt");
        Path targetLink = target.resolve("inner");
        Path refsLink = refs.resolve("13");
        dirLink(targetLink, outside);
        dirLink(refsLink, outside);
        Files.setLastModifiedTime(target, FileTime.from(OLD));
        Files.setLastModifiedTime(refs, FileTime.from(OLD));
        session(5, InterviewStatus.CANCELLED, 10);
        try {
            Summary s = job().runOnce(NOW);

            assertThat(target).doesNotExist();
            assertThat(refs).doesNotExist();
            assertThat(keep).hasContent("지우면 안 됨");
            assertThat(outside.resolve("sub/keep2.txt")).exists();
            assertThat(s).isEqualTo(new Summary(2, 20, 0, 0));   // 링크 대상의 크기는 해제 용량이 아니다
        } finally {
            Files.deleteIfExists(targetLink);
            Files.deleteIfExists(refsLink);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void deletes_a_folder_that_holds_a_dangling_junction() throws Exception {
        // 대상이 사라져 끊긴 정션(pnpm식 node_modules 등)이 든 폴더도 한 회차에 끝까지 지운다 — 못 지우면 매 회차 실패로 남는다
        Path gone = Files.createDirectories(tmp.resolve("gone"));
        Path target = folder("owner/repo/session-5", OLD, "x.txt");
        Path link = target.resolve("dangling");
        junction(link, gone);
        Files.delete(gone);
        Files.setLastModifiedTime(target, FileTime.from(OLD));
        session(5, InterviewStatus.CANCELLED, 10);
        try {
            Summary s = job().runOnce(NOW);

            assertThat(target).doesNotExist();
            assertThat(s).isEqualTo(new Summary(1, 10, 0, 0));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    /** 체크아웃 루트 안의 어느 단계가 링크로 바뀌어도 그 안으로 들어가지 않는다. */
    enum LinkedLevel {
        OWNER("owner", "repo/session-5/x.txt"),
        REPO("owner/repo", "session-5/x.txt"),
        SESSION("owner/repo/session-5", "x.txt"),
        REFS_ROOT("_refs", "5/x.txt"),
        REFS_ID("_refs/5", "x.txt");

        final String link;
        final String content;

        LinkedLevel(String link, String content) {
            this.link = link;
            this.content = content;
        }
    }

    @ParameterizedTest
    @EnumSource(LinkedLevel.class)
    void never_scans_through_a_link_standing_in_for_a_checkout_level(LinkedLevel level) throws Exception {
        Path real = Files.createDirectories(tmp.resolve("outside/real"));
        Path content = real.resolve(level.content);
        Files.createDirectories(content.getParent());
        Files.writeString(content, "지우면 안 됨");
        Path link = root.resolve(level.link);
        Files.createDirectories(link.getParent());
        dirLink(link, real);
        session(5, InterviewStatus.CANCELLED, 100);
        try {
            Summary s = job().runOnce(NOW);

            assertThat(content).hasContent("지우면 안 됨");
            assertThat(existsNoFollow(link)).isTrue();   // 링크는 건너뛸 뿐 지우지도 않는다
            assertThat(s).isEqualTo(new Summary(0, 0, 0, 0));
            verifyNoInteractions(sessionRepo);           // 후보가 없으니 DB도 안 본다
        } finally {
            Files.deleteIfExists(link);
        }
    }

    // ── 폴더 하나가 실패해도 나머지는 계속 지운다 ──────────

    /**
     * folder 하나의 삭제를 실패하게 만든다 — 반환한 객체를 닫으면 원상 복구된다.
     * Windows: 폴더 안 파일을 java.io 스트림으로 열어 둔다(FILE_SHARE_DELETE 없음).
     * POSIX: 부모 디렉터리의 쓰기 권한을 뺀다(root 계정은 막히지 않아 건너뛴다).
     */
    private static AutoCloseable blockDeletion(Path folder, Path fileInside) throws IOException {
        if (OS.WINDOWS.isCurrentOs()) {
            FileInputStream in = new FileInputStream(fileInside.toFile());
            return in::close;
        }
        Path parent = folder.getParent();
        assertThat(parent.toFile().setWritable(false)).isTrue();
        if (Files.isWritable(parent)) {   // root 등 권한으로 막히지 않는 환경
            assertThat(parent.toFile().setWritable(true)).isTrue();
            assumeTrue(false, "쓰기 권한으로 삭제를 막을 수 없는 환경(root 등)");
        }
        return () -> assertThat(parent.toFile().setWritable(true)).isTrue();
    }

    @Test
    void one_failing_folder_does_not_stop_the_others() throws Exception {
        Path stuck = folder("owner/repo/session-5", OLD, "x.txt");
        Path free = folder("owner2/repo2/session-6", OLD, "y.txt");
        session(5, InterviewStatus.CANCELLED, 10);
        session(6, InterviewStatus.CANCELLED, 10);

        try (AutoCloseable block = blockDeletion(stuck, stuck.resolve("x.txt"))) {
            Summary s = job().runOnce(NOW);

            assertThat(free).doesNotExist();
            assertThat(stuck).exists();
            assertThat(s.deleted()).isEqualTo(1);
            assertThat(s.failed()).isEqualTo(1);
        }

        // 막힌 원인이 사라지면 다음 회차에 마저 지운다
        Summary next = job().runOnce(NOW);
        assertThat(stuck).doesNotExist();
        assertThat(next.deleted()).isEqualTo(1);
        assertThat(next.failed()).isZero();
    }

    // ── 설정 연결: 기본값이 같아 키 오타가 조용히 묻히지 않게, 키마다 값을 바꿔 확인한다 ──

    /** 루트는 항상 임시 폴더로 — 기본값(운영자 실제 ~/netis-maker/interviews)을 가리키는 컨텍스트는 만들지 않는다. */
    private ApplicationContextRunner wired(String... properties) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=api", "app.interview.work-root=" + root)
                .withPropertyValues(properties)
                .withBean(InterviewSessionRepository.class, () -> sessionRepo)
                .withUserConfiguration(InterviewWorkPaths.class, InterviewCheckoutCleanupJob.class);
    }

    @Test
    void application_yml_documents_the_settings_and_the_schedule_reads_the_same_key() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(ctx -> {
                    Environment env = ctx.getEnvironment();
                    assertThat(env.getProperty("app.interview.checkout-cleanup.enabled")).isEqualTo("true");
                    assertThat(env.getProperty("app.interview.checkout-cleanup.cron")).isEqualTo("0 50 3 * * *");
                    assertThat(env.getProperty("app.interview.checkout-cleanup.retention-days")).isEqualTo("7");
                    Scheduled scheduled = InterviewCheckoutCleanupJob.class.getMethod("cleanup").getAnnotation(Scheduled.class);
                    assertThat(scheduled).isNotNull();
                    assertThat(env.resolvePlaceholders(scheduled.cron())).isEqualTo("0 50 3 * * *");
                });
        // 키를 바꾸면 스케줄이 따라온다 — 기본값과 같은 값이 아니라 다른 값으로 확인
        new ApplicationContextRunner()
                .withPropertyValues("app.interview.checkout-cleanup.cron=0 0 5 * * *")
                .run(ctx -> {
                    Scheduled scheduled = InterviewCheckoutCleanupJob.class.getMethod("cleanup").getAnnotation(Scheduled.class);
                    assertThat(ctx.getEnvironment().resolvePlaceholders(scheduled.cron())).isEqualTo("0 0 5 * * *");
                });
    }

    @Test
    void spring_wiring_reads_the_documented_keys() throws Exception {
        Path target = folder("owner/repo/session-5", OLD, "x.txt");
        // cleanup()은 실제 현재 시각을 쓰므로 행 시각도 현재 기준으로 만든다
        rows.add(new SessionState(5L, InterviewKind.QUESTION, InterviewStatus.CANCELLED, OffsetDateTime.now().minusDays(10)));

        // enabled=false → 아무것도 하지 않는다
        wired("app.interview.checkout-cleanup.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ctx.getBean(InterviewCheckoutCleanupJob.class).cleanup();
            assertThat(target.resolve("x.txt")).exists();
        });
        verifyNoInteractions(sessionRepo);

        // retention-days=0 → 기동 실패
        wired("app.interview.checkout-cleanup.retention-days=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().rootCause().isInstanceOf(IllegalArgumentException.class);
        });

        // retention-days를 늘리면 같은 폴더가 보존 기간 안이 된다 (기본 7일이면 10일 전 종료는 지운다)
        wired("app.interview.checkout-cleanup.retention-days=30").run(ctx -> {
            ctx.getBean(InterviewCheckoutCleanupJob.class).cleanup();
            assertThat(target.resolve("x.txt")).exists();
        });
        wired().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ctx.getBean(InterviewCheckoutCleanupJob.class).cleanup();
            assertThat(target).doesNotExist();
        });
    }
}
