package com.hamonsoft.netismaker.workerdaemon.patch;

import com.hamonsoft.netismaker.dto.ClosePatchSpec;
import com.hamonsoft.netismaker.dto.PatchInfo;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import com.hamonsoft.netismaker.util.FileTrees;
import com.hamonsoft.netismaker.workerdaemon.DeployPrebuilder;
import com.hamonsoft.netismaker.workerdaemon.GitOpsService;
import com.hamonsoft.netismaker.workerdaemon.GitRepoCache;
import com.hamonsoft.netismaker.workerdaemon.WorkerHttpClient;
import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import com.hamonsoft.netismaker.workerdaemon.WorktreeKind;
import com.hamonsoft.netismaker.workerdaemon.WorktreeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 종료 부분 패치 만들기 (스펙 2026-11-10). 병합 커밋("후")과 그 기준 쪽 부모("전")를 각각 읽기 전용 worktree에서
 * 미리 빌드 명령으로 빌드하고, 산출물 항목 해시를 비교해 바뀐 항목만 zip으로 묶어 API에 올린다.
 * 빌드는 전 → 후 차례로(동시 실행 없음). "후" 해시 목록은 다음 패치의 "전" 캐시로 남긴다.
 */
@Component
@Profile("worker")
@Slf4j
public class PatchBuilder {

    /** empty = 바뀐 항목이 없다(업로드 없음), fileCount = 패치에 실린 파일 수. */
    public record Outcome(boolean empty, int fileCount) {}

    /** 한 worktree에서 미리 빌드 명령을 돌려 비교할 산출물 경로를 돌려준다(테스트 seam). */
    @FunctionalInterface
    interface BuildFn {
        Path build(WorkerTaskResponse.PrebuildSpec spec, Path worktree)
                throws DeployPrebuilder.PrebuildException, InterruptedException;
    }

    /** git·worktree 호출(테스트 seam). */
    interface GitFn {
        File fetchPatchCommits(RepoRef ref, String baseBranch, String afterSha) throws IOException, InterruptedException;

        List<String> parents(File dir, String sha) throws IOException, InterruptedException;

        List<String> diffNameStatus(File dir, String before, String after) throws IOException, InterruptedException;

        File createAtCommit(File cacheDir, String repoKey, WorktreeKind kind, long taskId, String sha)
                throws IOException, InterruptedException;

        void discard(String repoKey, WorktreeKind kind, long taskId);
    }

    /** 실패 사유에 붙일 빌드 출력 꼬리 상한 — 전체 꼬리(8000자)는 워커 로그에만. */
    private static final int REASON_TAIL_CHARS = 1500;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Path closeRoot;
    private final ClosePatchWorkerProperties cfg;
    private final WorkerHttpClient http;
    private final BuildFn build;
    private final GitFn git;
    private final Clock clock;
    private final PatchScanner scanner;
    private final PatchManifestStore manifests;

    @Autowired
    public PatchBuilder(WorkerProperties props, ClosePatchWorkerProperties cfg, WorkerHttpClient http,
                        DeployPrebuilder prebuilder, GitRepoCache cache, GitOpsService gitOps,
                        WorktreeService worktrees) {
        this(Paths.get(props.reposDir()).resolveSibling("close"), cfg, http,
                (spec, worktree) -> prebuilder.buildOnly(spec, worktree, PatchBuilder::logBuildLine),
                new GitFn() {
                    @Override
                    public File fetchPatchCommits(RepoRef ref, String baseBranch, String afterSha)
                            throws IOException, InterruptedException {
                        return cache.fetchPatchCommits(ref, baseBranch, afterSha);
                    }

                    @Override
                    public List<String> parents(File dir, String sha) throws IOException, InterruptedException {
                        return gitOps.parents(dir, sha);
                    }

                    @Override
                    public List<String> diffNameStatus(File dir, String before, String after)
                            throws IOException, InterruptedException {
                        return gitOps.diffNameStatus(dir, before, after);
                    }

                    @Override
                    public File createAtCommit(File cacheDir, String repoKey, WorktreeKind kind, long taskId,
                                               String sha) throws IOException, InterruptedException {
                        return worktrees.createAtCommit(cacheDir, repoKey, kind, taskId, sha);
                    }

                    @Override
                    public void discard(String repoKey, WorktreeKind kind, long taskId) {
                        worktrees.discard(repoKey, kind, taskId);
                    }
                },
                Clock.system(SEOUL));
    }

    PatchBuilder(Path closeRoot, ClosePatchWorkerProperties cfg, WorkerHttpClient http, BuildFn build, GitFn git,
                 Clock clock) {
        this.closeRoot = closeRoot;
        this.cfg = cfg;
        this.http = http;
        this.build = build;
        this.git = git;
        this.clock = clock;
        // 전·후 모두 같은 무시 목록으로 읽는다 — 해시 목록 캐시 키에도 같은 목록이 들어간다
        this.scanner = new PatchScanner(cfg.ignore());
        this.manifests = new PatchManifestStore(closeRoot.resolve("patch-manifests"), cfg.keepManifests());
    }

    private static void logBuildLine(String line) {
        String masked = GitRemotes.mask(line);
        if (line.startsWith("[미리 빌드")) log.info("패치 빌드: {}", masked);
        else log.debug("패치 빌드: {}", masked);
    }

    public Outcome build(WorkerTaskResponse task, String mergeSha) throws PatchException, InterruptedException {
        ClosePatchSpec spec = task.closePatch();
        if (spec == null) throw new PatchException("부분 패치 지시가 없습니다");
        long id = task.id();

        Optional<PatchInfo> existing;
        try {
            existing = http.currentPatch(id);
        } catch (RuntimeException e) {
            throw new PatchException("부분 패치: 보관 여부 조회 실패 — " + detail(e));
        }
        if (existing.isPresent()) {   // 업로드 뒤 진행 보고 전에 끊긴 재시도
            log.info("부분 패치: 이미 보관됨 task={} {} 파일 {}개", id, existing.get().name(), existing.get().fileCount());
            return new Outcome(false, existing.get().fileCount());
        }
        if (mergeSha == null || mergeSha.isBlank()) {
            PatchBase.resolve(mergeSha, spec.baseSha(), List.of());   // 고정 문구로 실패한다
        }

        RepoRef ref = task.repoRef();
        String repoKey = GitRemotes.localKey(ref);
        WorkerTaskResponse.PrebuildSpec prebuild =
                new WorkerTaskResponse.PrebuildSpec(spec.command(), spec.artifact(), spec.toolchain());
        String key = PatchManifestStore.key(spec.command(), spec.toolchain(), spec.artifact(), cfg.ignore());
        Path work = closeRoot.resolve("task-" + id).resolve("patch");
        try {
            File cacheDir = git.fetchPatchCommits(ref, task.githubBranch(), mergeSha);
            String before = PatchBase.resolve(mergeSha, spec.baseSha(), git.parents(cacheDir, mergeSha));

            PatchTree beforeTree = manifests.load(repoKey, before, key).orElse(null);
            if (beforeTree == null) {
                Path beforeArtifact = buildAt(cacheDir, repoKey, WorktreeKind.PATCH_BEFORE, id, before, prebuild, "전");
                beforeTree = scanner.scan(beforeArtifact);
            } else {
                log.info("부분 패치: 전 커밋 {} 해시 목록 재사용 — 전 빌드 건너뜀 task={}", abbrev(before), id);
            }
            Path afterArtifact = buildAt(cacheDir, repoKey, WorktreeKind.PATCH_AFTER, id, mergeSha, prebuild, "후");
            PatchTree afterTree = scanner.scan(afterArtifact);

            PatchDelta delta = PatchDiff.diff(beforeTree, afterTree);
            manifests.save(repoKey, mergeSha, key, afterTree);
            log.info("부분 패치: task={} 전 {} → 후 {} 추가 {} · 변경 {} · 삭제 {}", id, abbrev(before), abbrev(mergeSha),
                    delta.added().size(), delta.changed().size(), delta.deleted().size());
            if (delta.isEmpty()) return new Outcome(true, 0);

            List<String> files = new ArrayList<>(delta.added());
            files.addAll(delta.changed());
            Path patchDir = work.resolve("patch");
            Files.createDirectories(patchDir);
            scanner.extract(afterArtifact, files, patchDir);
            PatchInfoDoc doc = new PatchInfoDoc(id, task.title(), repoAlias(task), before, mergeSha,
                    OffsetDateTime.now(clock.withZone(SEOUL)), spec.command(), delta,
                    sourceChanges(cacheDir, before, mergeSha));
            Path zip = work.resolve("patch.zip");
            PatchZipWriter.write(zip, patchDir, doc);
            verifyFileCount(zip, delta.fileCount());   // 빠진 파일이 있는 패치가 정상처럼 나가지 않게
            PatchInfo info = upload(id, delta.fileCount(), zip, sha256(zip));
            log.info("부분 패치 보관: task={} {} {}B 파일 {}개", id, info.name(), info.sizeBytes(), info.fileCount());
            return new Outcome(false, delta.fileCount());
        } catch (IOException e) {
            throw new PatchException("부분 패치 실패: " + detail(e));
        } finally {
            git.discard(repoKey, WorktreeKind.PATCH_BEFORE, id);
            git.discard(repoKey, WorktreeKind.PATCH_AFTER, id);
            FileTrees.DeleteResult d = FileTrees.deleteTree(work);
            if (d.failures() > 0) log.warn("부분 패치 작업 폴더 정리 실패 {}건: {}", d.failures(), work);
            try {
                Files.deleteIfExists(work.getParent());   // 빈 close/task-{id}만 — 다른 단계의 파일이 있으면 남는다
            } catch (IOException ignore) { /* 비어 있지 않거나 이미 없음 — 정리는 best-effort */ }
        }
    }

    private Path buildAt(File cacheDir, String repoKey, WorktreeKind kind, long id, String sha,
                         WorkerTaskResponse.PrebuildSpec prebuild, String side)
            throws IOException, InterruptedException, PatchException {
        File wt = git.createAtCommit(cacheDir, repoKey, kind, id, sha);
        log.info("부분 패치: {} 빌드 시작 task={} 커밋 {}", side, id, abbrev(sha));
        try {
            return build.build(prebuild, wt.toPath());
        } catch (DeployPrebuilder.PrebuildException e) {
            String tail = GitRemotes.mask(e.getLogTail()).strip();
            if (!tail.isEmpty()) log.warn("부분 패치 {} 빌드 출력 끝 task={}:\n{}", side, id, tail);
            if (tail.length() > REASON_TAIL_CHARS) tail = "…" + tail.substring(tail.length() - REASON_TAIL_CHARS);
            throw new PatchException("패치 빌드 실패(" + side + "): " + GitRemotes.mask(e.getMessage())
                    + (tail.isEmpty() ? "" : "\n" + tail));
        }
    }

    /** 만든 zip의 {@code patch/} 파일 항목 수가 비교 결과(추가 + 변경)와 같은지 본다. 다르면 올리지 않는다. */
    static void verifyFileCount(Path zip, int expected) throws IOException, PatchException {
        int actual = 0;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.isDirectory() && e.getName().startsWith("patch/")) actual++;
            }
        }
        if (actual != expected) {
            throw new PatchException("패치 파일 수가 맞지 않습니다: 기대 " + expected + ", 실제 " + actual);
        }
    }

    /** 소스 변경 목록(참고) — 못 읽으면 빈 목록. */
    private List<String> sourceChanges(File cacheDir, String before, String after) throws InterruptedException {
        try {
            return git.diffNameStatus(cacheDir, before, after);
        } catch (IOException e) {
            log.warn("부분 패치: 소스 변경 목록을 읽지 못함(빈 목록으로 진행) {}", detail(e));
            return List.of();
        }
    }

    private PatchInfo upload(long id, int fileCount, Path zip, String sha) throws PatchException, InterruptedException {
        try {
            return http.uploadPatch(id, fileCount, zip, sha, cfg.uploadTimeout());
        } catch (HttpTimeoutException e) {
            throw new PatchException("부분 패치: 업로드 실패 — " + (e instanceof HttpConnectTimeoutException
                    ? "API 서버 연결 시간 초과" : "시간 초과(" + cfg.uploadTimeout().toSeconds() + "초)"));
        } catch (IOException | RuntimeException e) {
            throw new PatchException("부분 패치: 업로드 실패 — " + detail(e));
        }
    }

    private static String repoAlias(WorkerTaskResponse task) {
        String alias = task.closeContext() == null ? null : task.closeContext().repoAlias();
        return alias == null || alias.isBlank() ? task.githubRepo() : alias;
    }

    private static String detail(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        m = GitRemotes.mask(m);
        return m.length() > 300 ? m.substring(0, 300) : m;
    }

    private static String abbrev(String sha) {
        return sha == null || sha.length() <= 7 ? String.valueOf(sha) : sha.substring(0, 7);
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
