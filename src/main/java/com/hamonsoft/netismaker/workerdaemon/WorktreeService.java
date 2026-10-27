package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.git.GitRemotes;
import com.hamonsoft.netismaker.git.RepoRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 구현 단계의 worktree 라이프사이클.
 *
 *  레이아웃:
 *    ~/netis-maker/repos/{owner}/{repo}        ← GitRepoCache (분석 + 베이스 fetch)
 *    ~/netis-maker/worktrees/{owner}/{repo}/{slug}  ← 본 서비스
 *    ~/netis-maker/worktrees/_refs/task-{id}/{카탈로그 id}  ← 구현용 읽기 전용 참조 repo(고정 커밋 detached,
 *        대상 worktree 밖 — 스펙 2026-10-24 §6.2). 원본은 그 참조 repo의 레포 캐시(대상과 같은 캐시·락).
 *
 *  브랜치 컨벤션:
 *    netismaker/task-{id}-{slug-of-title}[-r{시도 번호}]  (2회째부터, 스펙 2026-10-15 §6.1)
 *
 *  멱등성: 같은 task를 다시 시도하면 기존 worktree 제거 후 새로 생성. 디스크에는 항상 단일 인스턴스.
 *  정리: 성공한 작업은 호출자가 끝에 discard로 지운다(구현=PR 생성 뒤, 배포=빌드·실행 뒤, 디자인=수확 뒤).
 *  실패분은 디버그용으로 남기고 WorktreeCleanupJob이 보존 기간(기본 7일) 뒤 지운다.
 */
@Component
@Profile("worker")
@Slf4j
public class WorktreeService {

    private static final long GIT_TIMEOUT_SECONDS = 600;
    /** 네트워크를 쓰지 않는 참조 git(config·status·rev-parse)의 상한. */
    private static final long LOCAL_GIT_TIMEOUT_SECONDS = 120;

    /**
     * 참조 worktree의 맨 위 폴더 이름 — {worktree-root}/_refs. GitHub 사용자명은 '_'로 시작할 수 없고 GitLab은
     * _gitlab 아래라 대상 {localKey} 폴더와 겹치지 않는다.
     */
    static final String REFS_DIR = "_refs";
    /** 참조 worktree 전용 push URL — 헬퍼가 없는 스킴이라 실수 push가 실패한다(공유 캐시·대상 worktree에는 쓰지 않는다). */
    static final String REF_PUSH_URL_BLOCKED = "netis-read-only-ref://push-disabled";
    /** porcelain v1 상태 줄("XY 경로") — 합쳐 받은 stderr 경고 줄은 변경으로 세지 않는다. */
    private static final Pattern STATUS_LINE = Pattern.compile("[ MTADRCU?!]{2} .+");

    /** worktree를 떼고 지울 때의 git 호출 — 대상은 평소 git, 참조는 무프롬프트 참조 git. */
    @FunctionalInterface
    private interface WorktreeGit {
        ProcessRunner.Result run(File dir, String... args) throws IOException, InterruptedException;
    }

    private static final WorktreeGit TARGET_GIT = (dir, args) -> {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        return ProcessRunner.run(dir, cmd, GIT_TIMEOUT_SECONDS);
    };
    private static final WorktreeGit REF_GIT =
            (dir, args) -> GitRepoCache.runRefGit(dir, GitRepoCache.refGit(args), GIT_TIMEOUT_SECONDS);

    /**
     * 워커 임시 파일 — 에이전트 커밋(SDD 태스크 커밋 포함)에 섞이지 않게 info/exclude에 둔다 (스펙 2026-10-15 §6.1).
     * 앞의 `/`로 레포 루트에만 건다: 없으면 대상 레포 하위 폴더의 같은 이름(`src/.design/` 등)까지 맞아, 거기에 새로 만든
     * 파일이 모든 방식의 커밋에서 조용히 빠진다.
     */
    static final List<String> WORKER_EXCLUDES = List.of("/.netis/", "/.design/", "/.netis-deploy-env.json");

    private final WorkerProperties props;
    private final GitRepoCache repos;

    public WorktreeService(WorkerProperties props, GitRepoCache repos) {
        this.props = props;
        this.repos = repos;
    }

    /** 1회째 시도 — 6인자 create에 attempt=1로 위임한다. */
    public CreatedWorktree create(File repoCacheDir, String repoKey,
                                  String baseBranch, long taskId, String title)
            throws IOException, InterruptedException {
        return create(repoCacheDir, repoKey, baseBranch, taskId, title, 1);
    }

    /**
     * task용 worktree를 새 브랜치로 생성. 기존 path/branch가 있으면 강제로 정리 후 재생성.
     *
     * 다중 워커 동시성: GitRepoCache.withRepoLock으로 보호.
     * 같은 머신의 두 워커가 동일 repo의 다른 task를 동시에 처리할 때
     * `.git/worktrees/` 메타 파일 동시 수정 race 방지.
     *
     * @param repoCacheDir GitRepoCache.ensureFresh가 반환한 디렉토리 (origin/{baseBranch} 최신화 상태)
     * @param repoKey      GitRemotes.localKey 값 — worktree path 구성용
     * @param baseBranch   분석 대상 브랜치 (origin/{baseBranch}을 분기점으로)
     * @param taskId       브랜치명에 포함
     * @param title        브랜치명 슬러그 생성용
     * @param attempt      구현 시도 번호 — 2 이상이면 브랜치 이름에 -r{attempt}
     * @return 생성된 worktree 디렉토리 + 브랜치명
     */
    public CreatedWorktree create(File repoCacheDir, String repoKey,
                                  String baseBranch, long taskId, String title, int attempt)
            throws IOException, InterruptedException {
        return repos.withRepoLock(repoKey,
                () -> doCreate(repoCacheDir, repoKey, baseBranch, taskId, title, attempt));
    }

    /**
     * {prefix}task-{id}[-{slug}][-r{attempt}] — 재시도는 새 브랜치로 (이전 시도가 push한 브랜치와 겹치면
     * 강제 아닌 push가 거부된다). deleteTaskBranches의 'task-{id}-' 접두 매칭이 그대로 덮는다.
     */
    static String branchName(String prefix, long taskId, String slug, int attempt) {
        return prefix + "task-" + taskId
                + (slug.isEmpty() ? "" : "-" + slug)
                + (attempt >= 2 ? "-r" + attempt : "");
    }

    private CreatedWorktree doCreate(File repoCacheDir, String repoKey,
                                     String baseBranch, long taskId, String title, int attempt)
            throws IOException, InterruptedException {
        String branchName = branchName(props.branchPrefix(), taskId, sanitizeForBranch(title), attempt);

        Path worktreeDir = worktreeDir(repoKey, WorktreeKind.TASK, taskId);
        Files.createDirectories(worktreeDir.getParent());

        // 멱등성: 같은 task를 재시도하는 경우 기존 worktree/브랜치 정리
        if (Files.exists(worktreeDir, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("기존 worktree 발견, 강제 제거: {}", worktreeDir);
            removeWorktree(TARGET_GIT, repoCacheDir, worktreeDir, false);
        }
        // 같은 이름 브랜치가 캐시에 남아있으면 제거 (워크트리 없는 dangling 브랜치)
        try {
            ProcessRunner.run(repoCacheDir, List.of("git", "branch", "-D", branchName),
                    GIT_TIMEOUT_SECONDS);
        } catch (Exception ignore) {
            // 없으면 fail — 정상
        }

        ProcessRunner.requireSuccess(repoCacheDir,
                List.of("git", "worktree", "add", "-b", branchName,
                        worktreeDir.toString(), "origin/" + baseBranch),
                GIT_TIMEOUT_SECONDS);

        log.info("worktree 생성: task={} branch={} dir={}", taskId, branchName, worktreeDir);
        return new CreatedWorktree(worktreeDir.toFile(), branchName);
    }

    /**
     * WORKER_EXCLUDES를 레포 info/exclude에 없는 줄만 더한다(worktree면 공용 git 디렉터리의 파일 — 워커 전용 이름이라
     * 다른 작업에도 무해). 실패는 경고만 남기고 false — 호출부가 SDD를 리뷰로 대신 진행한다 (스펙 §6.5).
     */
    public boolean ensureWorkerExcludes(File worktreeDir) {
        try {
            String raw = ProcessRunner.requireSuccess(worktreeDir,
                    List.of("git", "rev-parse", "--git-path", "info/exclude"), GIT_TIMEOUT_SECONDS).trim();
            Path exclude = Path.of(raw);
            if (!exclude.isAbsolute()) exclude = worktreeDir.toPath().resolve(exclude);
            Files.createDirectories(exclude.getParent());
            String existing = Files.exists(exclude) ? Files.readString(exclude) : "";
            List<String> lines = existing.lines().map(String::trim).toList();
            List<String> missing = WORKER_EXCLUDES.stream().filter(e -> !lines.contains(e)).toList();
            if (missing.isEmpty()) return true;
            StringBuilder add = new StringBuilder();
            if (!existing.isEmpty() && !existing.endsWith("\n")) add.append('\n');
            add.append("# netisMaker 워커 임시 파일 — 구현 커밋에서 제외\n");
            missing.forEach(m -> add.append(m).append('\n'));
            Files.writeString(exclude, add.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("info/exclude 기록 실패 (SDD 요청은 리뷰로 대신 진행): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 배포용 worktree 생성. 구현용 create와 달리 새 브랜치를 만들지 않고
     * 기존 head 브랜치(origin/{headBranch})를 detached 체크아웃한다 (빌드만 필요).
     * 멱등: 기존 deploy worktree 있으면 강제 제거 후 재생성.
     *
     * @param repoCacheDir GitRepoCache.ensureFresh가 반환한 디렉토리 (origin/{headBranch} fetch 상태)
     * @param repoKey      GitRemotes.localKey 값
     * @param headBranch   배포 대상 브랜치 (PR head)
     * @param taskId       worktree 경로 구성용
     * @return 생성된 worktree 디렉토리 (브랜치명은 의미 없어 dir만 사용)
     */
    public File createForDeploy(File repoCacheDir, String repoKey,
                                String headBranch, long taskId)
            throws IOException, InterruptedException {
        return repos.withRepoLock(repoKey,
                () -> doCreateForDeploy(repoCacheDir, repoKey, headBranch, taskId));
    }

    private File doCreateForDeploy(File repoCacheDir, String repoKey,
                                   String headBranch, long taskId)
            throws IOException, InterruptedException {
        Path worktreeDir = worktreeDir(repoKey, WorktreeKind.DEPLOY, taskId);
        Files.createDirectories(worktreeDir.getParent());

        if (Files.exists(worktreeDir, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("기존 deploy worktree 발견, 강제 제거: {}", worktreeDir);
            removeWorktree(TARGET_GIT, repoCacheDir, worktreeDir, false);
        }

        ProcessRunner.requireSuccess(repoCacheDir,
                List.of("git", "worktree", "add", "--force", "--detach",
                        worktreeDir.toString(), "origin/" + headBranch),
                GIT_TIMEOUT_SECONDS);

        log.info("deploy worktree 생성: task={} branch={} dir={}", taskId, headBranch, worktreeDir);
        return worktreeDir.toFile();
    }

    /**
     * 디자인용 worktree. 새 브랜치 없이 origin/{baseBranch} detached 체크아웃 (목업 생성만, push 없음).
     * 경로 design-{id} — 구현 worktree(task-{id})와 분리되어 재실행/후속 구현과 충돌 없음.
     */
    public File createForDesign(File repoCacheDir, String repoKey,
                                String baseBranch, long taskId)
            throws IOException, InterruptedException {
        return repos.withRepoLock(repoKey,
                () -> doCreateForDesign(repoCacheDir, repoKey, baseBranch, taskId));
    }

    private File doCreateForDesign(File repoCacheDir, String repoKey,
                                   String baseBranch, long taskId)
            throws IOException, InterruptedException {
        Path worktreeDir = worktreeDir(repoKey, WorktreeKind.DESIGN, taskId);
        Files.createDirectories(worktreeDir.getParent());
        if (Files.exists(worktreeDir, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("기존 design worktree 발견, 강제 제거: {}", worktreeDir);
            removeWorktree(TARGET_GIT, repoCacheDir, worktreeDir, false);
        }
        ProcessRunner.requireSuccess(repoCacheDir,
                List.of("git", "worktree", "add", "--force", "--detach",
                        worktreeDir.toString(), "origin/" + baseBranch),
                GIT_TIMEOUT_SECONDS);
        log.info("design worktree 생성: task={} dir={}", taskId, worktreeDir);
        return worktreeDir.toFile();
    }

    public Path worktreeDir(String repoKey, WorktreeKind kind, long taskId) {
        return Paths.get(props.worktreeRoot(), repoKey, kind.dirName(taskId));
    }

    /**
     * 구현용 참조 worktree 폴더 — {worktree-root}/_refs/task-{id}/{카탈로그 id}. 대상 worktree 밖 짧은 경로다:
     * 안에 두면 {@code git add -A}가 쓸어 담고 그 안의 CLAUDE.md·.claude/rules가 로드되며, Windows 260자 한도에도 걸린다.
     */
    public Path refWorktreeDir(long taskId, long catalogId) {
        return Paths.get(props.worktreeRoot(), REFS_DIR, WorktreeKind.TASK.dirName(taskId),
                String.valueOf(catalogId));
    }

    /**
     * claim의 참조 → RepoRef. repoHost가 "github"·"gitlab"이면 그대로, 비었거나 모르는 값이면 대상 스냅샷과 같은 규칙
     * (API도 repoHost를 같은 fromSnapshot으로 채우므로 운영 동작은 같다). 캐시 키는 항상 GitRemotes.localKey(이 값).
     */
    public static RepoRef repoRefOf(WorkerTaskResponse.TaskRefRepo ref) {
        String host = ref.repoHost();
        if ("github".equals(host) || "gitlab".equals(host)) {
            return new RepoRef(host, ref.githubRepo(), ref.gitUrl());
        }
        return RepoRef.fromSnapshot(ref.githubRepo(), ref.gitUrl());
    }

    public enum DiscardResult { REMOVED, ABSENT, GIT_LOCKED }

    /**
     * 작업이 끝난 worktree 정리(best-effort) — 레포 락 안에서 discardUnderLock.
     * 실패는 경고만 남기고 삼킨다: 정리 실패가 작업 결과를 바꾸면 안 되고, 남은 폴더는
     * WorktreeCleanupJob이 보존 기간 뒤 다시 시도한다.
     */
    public void discard(String repoKey, WorktreeKind kind, long taskId) {
        try {
            DiscardResult r = repos.withRepoLock(repoKey, () -> discardUnderLock(repoKey, kind, taskId));
            if (r == DiscardResult.GIT_LOCKED) {
                log.info("worktree 정리 건너뜀(git worktree lock): {}", worktreeDir(repoKey, kind, taskId));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("worktree 정리 실패(주기 정리가 다시 시도): {} ({})",
                    worktreeDir(repoKey, kind, taskId), e.getMessage());
        }
    }

    /**
     * 제거 절차. 호출자가 repoKey의 레포 락을 쥐고 있어야 한다(WorktreeCleanupJob은 tryWithRepoLock 안에서 부른다).
     *  1) git worktree lock이 걸려 있으면 손대지 않는다(사람이 일부러 남긴 것)
     *  2) 캐시가 있으면 git worktree remove --force
     *  3) 폴더가 남았으면 링크를 따라가지 않는 재귀 삭제(Windows 읽기 전용 포함) — 하나라도 실패하면 IOException
     *  4) 캐시가 있으면 git worktree prune, 구현 worktree면 그 작업의 로컬 브랜치 삭제(이미 원격에 푸시됨)
     */
    public DiscardResult discardUnderLock(String repoKey, WorktreeKind kind, long taskId)
            throws IOException, InterruptedException {
        Path dir = worktreeDir(repoKey, kind, taskId);
        Path cache = Paths.get(props.reposDir(), repoKey);
        boolean hasCache = Files.isDirectory(cache.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
        boolean present = Files.exists(dir, LinkOption.NOFOLLOW_LINKS);

        if (present && isGitLocked(dir)) return DiscardResult.GIT_LOCKED;
        removeWorktree(TARGET_GIT, hasCache ? cache.toFile() : null, dir, true);
        if (hasCache && kind == WorktreeKind.TASK) deleteTaskBranches(cache.toFile(), taskId);
        if (present) log.info("worktree 정리: {}", dir);
        return present ? DiscardResult.REMOVED : DiscardResult.ABSENT;
    }

    /**
     * dir의 worktree를 캐시에서 떼고(git worktree remove --force — 실패는 무시) 남은 폴더를 지운다. 대상·참조의
     * 재생성 전 정리와 작업 끝 정리가 모두 이 절차를 쓴다. 폴더 삭제는 심볼릭 링크·Windows 정션을 따라가지 않고 링크만
     * 지운다(git worktree remove --force가 정션을 남겨 둠 — 따라가면 worktree 밖이 지워진다).
     *  - discard=false(재생성 전): 폴더 삭제 실패는 경고만 — 이어지는 worktree add가 실패로 드러낸다
     *  - discard=true(작업 끝 정리): 폴더 삭제 실패면 IOException, 캐시가 있으면 끝에 git worktree prune
     * cacheDir가 null이면 git 없이 폴더만 지운다.
     */
    private static void removeWorktree(WorktreeGit git, File cacheDir, Path dir, boolean discard)
            throws IOException, InterruptedException {
        if (cacheDir != null && Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            try {
                ProcessRunner.Result r = git.run(cacheDir, "worktree", "remove", "--force", dir.toString());
                if (r.exitCode() != 0) log.debug("git worktree remove 실패(폴더 직접 삭제로 진행): {}", r.stdout());
            } catch (IOException e) {
                log.warn("worktree remove 실패 (계속): {}", e.getMessage());
            }
        }
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            RepoCacheCleanupJob.DeleteResult d = RepoCacheCleanupJob.deleteTree(dir);
            if (d.failures() > 0) {
                if (discard) throw new IOException("worktree 폴더 삭제 실패 " + d.failures() + "건: " + dir);
                log.warn("기존 worktree 폴더 삭제 미완료: {} ({}개 항목 실패)", dir, d.failures());
            }
        }
        if (discard && cacheDir != null) git.run(cacheDir, "worktree", "prune");
    }

    /**
     * 참조 worktree를 고정 커밋에 detached로 만든다(스펙 2026-10-24 §6.2). 그 참조의 레포 락을 **한 번만** 잡고 그 안에서
     * 고정 커밋 준비 → 남은 폴더 정리 → {@code worktree add --force --detach} → 이 worktree에만 push URL 무효화. 기준선은 락을
     * 놓은 뒤에 잡는다 — 그 worktree 전용 index만 건드리고({@link #refChangeWarning}도 락 없이 같은 명령을 쓴다), 큰 옛 레포의 첫
     * status는 수십 초가 걸려 그동안 같은 레포를 쓰는 다른 워커 프로세스가 60초 락 대기로 실패할 수 있어서다. 기준선을 못 잡으면 그
     * 참조를 평소 정리 경로({@link #discardRefWorktree})로 지우고 같은 예외를 던진다(준비 실패 경로는 그대로).
     * sha·브랜치 모양은 락 전에 본다. 대상 {@code ensureFresh}는 쓰지 않는다(공유 캐시의 HEAD·origin/*를 옮기지 않게).
     * git은 전부 무프롬프트 참조 git(체크아웃 필터의 자격증명 요청도 묻지 않게). 실패는 가린 출력의 IOException.
     */
    public RefWorktree createRefWorktree(long taskId, WorkerTaskResponse.TaskRefRepo ref)
            throws IOException, InterruptedException {
        String sha = GitRepoCache.checkRefTarget(ref.branch(), ref.sha());
        RepoRef repo = repoRefOf(ref);
        String refKey = GitRemotes.localKey(repo);
        Path dir = refWorktreeDir(taskId, ref.catalogId());
        repos.withRepoLock(refKey, () -> {
            File cacheDir = repos.prepareRefCommitUnderLock(repo, ref.branch(), sha);
            Files.createDirectories(dir.getParent());
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("기존 참조 worktree 발견, 강제 제거: {}", dir);
                removeWorktree(REF_GIT, cacheDir, dir, false);
            }
            GitRepoCache.requireRefGit(cacheDir,
                    GitRepoCache.refGit("worktree", "add", "--force", "--detach", dir.toString(), sha),
                    GIT_TIMEOUT_SECONDS);
            // 실수 push 막기 — worktree 전용 설정이라 공유 캐시 본체·다른 worktree(대상 작업)에는 걸리지 않는다
            GitRepoCache.requireRefGit(cacheDir,
                    GitRepoCache.refGit("config", "extensions.worktreeConfig", "true"), LOCAL_GIT_TIMEOUT_SECONDS);
            GitRepoCache.requireRefGit(dir.toFile(),
                    GitRepoCache.refGit("config", "--worktree", "remote.origin.pushurl", REF_PUSH_URL_BLOCKED),
                    LOCAL_GIT_TIMEOUT_SECONDS);
            return null;
        });
        List<String> baseline;
        try {
            baseline = refStatus(dir.toFile());
        } catch (IOException | InterruptedException | RuntimeException e) {
            discardRefWorktree(taskId, ref);
            throw e;
        }
        log.info("참조 worktree 생성: task={} {}({})@{} dir={}", taskId, ref.alias(), ref.branch(),
                sha.substring(0, 7), dir);
        return new RefWorktree(ref.catalogId(), ref.alias(), ref.branch(), sha, refKey, dir.toFile(), baseline);
    }

    /**
     * claude가 끝난 뒤 참조 폴더가 바뀌었는지 — 만든 직후 기준선과 비교(스펙 §6.6). 락 없이 본다(그 worktree 전용 index만 건드림).
     * 예외를 던지지 않는다: 폴더가 없으면 "참조 폴더가 지워짐", git을 못 돌리면 "변경 확인 실패".
     */
    public Optional<String> refChangeWarning(RefWorktree ref) {
        Path dir = ref.dir().toPath();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return Optional.of(ref.alias() + ": 참조 폴더가 지워짐");
        try {
            // .git 파일이 없으면 git이 위쪽 폴더의 다른 repo를 볼 수 있다 — 확인할 수 없음으로 친다
            if (!Files.isRegularFile(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(".git 파일 없음");
            }
            List<String> now = refStatus(dir.toFile());
            String head = GitRepoCache.requireRefGit(dir.toFile(), GitRepoCache.refGit("rev-parse", "HEAD"),
                            LOCAL_GIT_TIMEOUT_SECONDS)
                    .lines().map(String::strip).filter(GitRepoCache::isFullSha).reduce((a, b) -> b)
                    .orElseThrow(() -> new IOException("HEAD를 읽지 못했습니다"));
            return changeWarning(ref.alias(), ref.baseline(), now, ref.sha(), head);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(ref.alias() + ": 변경 확인 실패");
        } catch (Exception e) {
            log.warn("참조 변경 확인 실패: {} ({})", dir, e.getMessage());
            return Optional.of(ref.alias() + ": 변경 확인 실패");
        }
    }

    /**
     * HEAD가 고정 커밋이 아니면 "{별칭}: HEAD가 고정 커밋과 다름"(파일 수보다 우선), 아니면 기준선에 없던 상태 줄이 N개면
     * "{별칭}: 파일 {N}개". 기준선과 비교하므로 checkout 직후부터 dirty로 보이는 repo(CRLF·.gitattributes)는 경고하지 않는다.
     */
    static Optional<String> changeWarning(String alias, List<String> baseline, List<String> now,
                                          String pinnedSha, String head) {
        if (head == null || !head.equalsIgnoreCase(pinnedSha)) {
            return Optional.of(alias + ": HEAD가 고정 커밋과 다름");
        }
        Set<String> before = new HashSet<>(baseline);
        long added = now.stream().filter(line -> !before.contains(line)).count();
        return added > 0 ? Optional.of(alias + ": 파일 " + added + "개") : Optional.empty();
    }

    /** 무시 파일까지 포함한 상태 줄 — 참조 폴더에서 빌드·설치한 흔적도 잡는다. */
    private static List<String> refStatus(File dir) throws IOException, InterruptedException {
        String out = GitRepoCache.requireRefGit(dir,
                GitRepoCache.refGit("status", "--porcelain", "--untracked-files=all", "--ignored=matching"),
                LOCAL_GIT_TIMEOUT_SECONDS);
        return out.lines().filter(line -> STATUS_LINE.matcher(line).matches()).toList();
    }

    /**
     * 작업이 끝난 참조 worktree 정리(best-effort, 성공·실패 모든 경로) — 대상용 discard와 별개(스펙 §6.8).
     * 폴더가 없으면 락을 잡지 않는다. 있으면 원본 캐시의 레포 락 안에서 discardRefUnderLock, 락 밖에서 비게 된
     * _refs/task-{id}를 지운다. 실패는 경고만 — 남은 폴더는 WorktreeCleanupJob이 다시 시도한다.
     */
    public void discardRefWorktree(long taskId, WorkerTaskResponse.TaskRefRepo ref) {
        Path dir = null;
        try {
            dir = refWorktreeDir(taskId, ref.catalogId());
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                String refKey = GitRemotes.localKey(repoRefOf(ref));
                Path refDir = dir;
                Path cacheDir = Paths.get(props.reposDir(), refKey);
                DiscardResult r = repos.withRepoLock(refKey, () -> discardRefUnderLock(refDir, cacheDir));
                if (r == DiscardResult.GIT_LOCKED) log.info("참조 worktree 정리 건너뜀(git worktree lock): {}", dir);
            }
            deleteIfEmpty(dir.getParent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("참조 worktree 정리 실패(주기 정리가 다시 시도): {} ({})", dir, e.getMessage());
        }
    }

    /**
     * 참조 worktree 제거 절차. cacheDir가 있으면 호출자가 그 캐시의 레포 락을 쥐고 있어야 한다.
     *  - cacheDir에 .git 디렉터리가 있으면: git worktree lock이면 손대지 않음 → worktree remove --force(실패 무시)
     *    → 남은 트리 삭제(링크를 따라가지 않음, 실패면 IOException) → worktree prune. detached라 지울 브랜치는 없다.
     *  - cacheDir가 null이거나 .git이 없으면(원본 캐시를 모름 — 정리 잡): 잠금 검사·git 없이 폴더만 지운다.
     */
    public DiscardResult discardRefUnderLock(Path refDir, Path cacheDir) throws IOException, InterruptedException {
        boolean hasCache = cacheDir != null && Files.isDirectory(cacheDir.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
        boolean present = Files.exists(refDir, LinkOption.NOFOLLOW_LINKS);
        if (present && hasCache && isGitLocked(refDir)) return DiscardResult.GIT_LOCKED;
        removeWorktree(REF_GIT, hasCache ? cacheDir.toFile() : null, refDir, true);
        if (present) log.info("참조 worktree 정리: {}", refDir);
        return present ? DiscardResult.REMOVED : DiscardResult.ABSENT;
    }

    /** 비었을 때만 지운다 — 같은 작업의 다른 참조가 남아 있으면 그대로 둔다. */
    private static void deleteIfEmpty(Path dir) {
        try {
            Files.deleteIfExists(dir);
        } catch (DirectoryNotEmptyException ignore) {
            // 같은 작업의 다른 참조 폴더가 아직 있다
        } catch (IOException e) {
            log.debug("빈 참조 작업 폴더 삭제 실패(계속): {} ({})", dir, e.getMessage());
        }
    }

    /**
     * {branch-prefix}task-{id} 와 {branch-prefix}task-{id}-{slug} 만 지운다 — task-1 정리가 task-12-…를 지우지 않게
     * '-' 경계로 매칭한다(doCreate의 브랜치 규칙과 같다). 다른 worktree가 체크아웃 중이면 git이 거부 → 경고만.
     */
    private void deleteTaskBranches(File cacheDir, long taskId) throws IOException, InterruptedException {
        String base = "refs/heads/" + props.branchPrefix() + "task-" + taskId;
        String out = ProcessRunner.requireSuccess(cacheDir,
                List.of("git", "for-each-ref", "--format=%(refname:short)", base, base + "-*"),
                GIT_TIMEOUT_SECONDS);
        String exact = props.branchPrefix() + "task-" + taskId;
        for (String branch : out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            if (!branch.equals(exact) && !branch.startsWith(exact + "-")) continue;
            ProcessRunner.Result r = ProcessRunner.run(cacheDir, List.of("git", "branch", "-D", branch),
                    GIT_TIMEOUT_SECONDS);
            if (r.exitCode() != 0) log.warn("로컬 브랜치 삭제 실패: {} ({})", branch, r.stdout().trim());
        }
    }

    /**
     * worktree의 .git 파일이 가리키는 메타 디렉터리에 locked 표시가 있으면 true.
     * .git이 없으면 false. 읽을 수 없거나 형식이 다르면 true — 모르면 안 지운다.
     * gitdir은 절대경로 또는(git 2.48+ relative 모드) worktree 기준 상대경로.
     */
    static boolean isGitLocked(Path worktreeDir) {
        Path gitFile = worktreeDir.resolve(".git");
        if (Files.notExists(gitFile, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            String line = Files.readString(gitFile).trim();
            if (!line.startsWith("gitdir:")) return true;
            Path meta = worktreeDir.resolve(line.substring("gitdir:".length()).trim()).normalize();
            return !Files.notExists(meta.resolve("locked"), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /** 브랜치명에 들어갈 슬러그. 영문/숫자/-/_ 만 통과, 30자 제한. */
    static String sanitizeForBranch(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean lastDash = false;
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                lastDash = false;
            } else if (c == '-' || c == '_') {
                out.append(c);
                lastDash = (c == '-');
            } else if (Character.isWhitespace(c) || c == '/' || c == '\\') {
                if (!lastDash && out.length() > 0) {
                    out.append('-');
                    lastDash = true;
                }
            }
            // 그 외(한글, 특수문자)는 생략
            if (out.length() >= 30) break;
        }
        // 양끝 - 제거
        while (out.length() > 0 && out.charAt(0) == '-') out.deleteCharAt(0);
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.toString();
    }

    public record CreatedWorktree(File dir, String branchName) {}

    /**
     * 구현용 참조 worktree 하나. sha는 소문자 고정 커밋, repoKey는 원본 레포 캐시의 localKey,
     * baseline은 만든 직후의 상태 줄(무시 파일 포함) — refChangeWarning이 이것과 비교한다.
     */
    public record RefWorktree(long catalogId, String alias, String branch, String sha, String repoKey, File dir,
                              List<String> baseline) {}
}
