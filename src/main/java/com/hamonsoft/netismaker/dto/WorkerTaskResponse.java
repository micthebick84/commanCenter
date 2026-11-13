package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskDesign;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;
import com.hamonsoft.netismaker.git.RepoRef;

import java.util.List;

/**
 * 워커가 작업을 claim했을 때 받는 페이로드.
 *
 *  kind=ANALYSIS       → 분석 prompt 실행 (analysis/headBranch/headSha null)
 *  kind=IMPLEMENTATION → worktree에서 구현 prompt 실행 (분석 산출물 동봉)
 *  kind=DEPLOY         → head 브랜치(headBranch@headSha)를 빌드해 docker 배포 (envVars 주입)
 *  kind=UNDEPLOY       → 컨테이너 netis-task-{id} 중지 (id만 사용)
 *  kind=DESIGN         → 목업 생성 (DESIGN.md + screens/*.html). designSystemProjectId·designOutputProjectId·designUrl은
 *                        예전 Claude Design용 값이라 2026-10-31부터 새 워커는 채우지도 쓰지도 않는다
 *                        (스펙 2026-10-31-design-phase-without-claude-design-design.md)
 *  kind=CLOSE          → 작업 종료: PR 병합 → 배포 중지 → 노트 → 브랜치 정리 (prNumber/headSha/closeProgress/closeContext/closeArtifact 동봉)
 */
public record WorkerTaskResponse(
        Long id,
        String githubRepo,
        String githubBranch,
        String title,
        String description,
        Kind kind,
        List<TaskMcpSpec> mcpsExtra,
        String analysisMarkdown,
        String subtasksJson,
        String headBranch,
        String headSha,
        List<EnvVar> envVars,
        String model,
        String effort,
        String designMarkdown,
        String mockupFilesJson,
        String feedbackHistoryJson,
        String designUrl,
        String designSystemProjectId,
        String designOutputProjectId,
        /** 정식 Git URL 스냅샷. 카탈로그 도입 전 작업·구버전 API는 null → GitHub로 간주. */
        String gitUrl,
        /** "github" | "gitlab" — gitUrl에서 판정한 값. null이면 github. */
        String repoHost,
        /**
         * IMPLEMENTATION 전용: 인터뷰에서 확정된 플랜 본문(interview_plan.plan_markdown). 인터뷰 없이 온
         * 작업·구버전 API는 null → 구현 프롬프트의 {plan_section}이 비어 기존 동작 그대로.
         */
        String planMarkdown,
        /** CLOSE 전용: 병합할 PR/MR 번호·URL. 다른 kind는 null. */
        Integer prNumber,
        String prUrl,
        /** CLOSE 전용: 이미 끝난 단계(CloseStep 콤마 목록) — 재claim한 워커가 건너뛴다. */
        String closeProgress,
        /** CLOSE 전용: 노트 재료. */
        CloseContext closeContext,
        /** CLOSE 전용: 산출물 지시. null이면 워커가 산출물 단계를 건너뛴다 (스펙 2026-10-14 §3). */
        CloseArtifactSpec closeArtifact,
        /** IMPLEMENTATION·DESIGN 전용: DB MCP 지시 (스펙 2026-10-14 §6.4). WorkerController가 claim 커밋 뒤 채운다. */
        DbMcpGrant dbMcp,
        /**
         * IMPLEMENTATION 전용: 구현 방식·시도 번호·상한 (스펙 2026-10-15 §5.3). 다른 kind·구버전 API는 null —
         * 워커는 직접·1회째·자기 설정 상한으로 돈다.
         */
        ImplementationSpec implementation,
        /**
         * IMPLEMENTATION 전용: 읽기 전용 참조 repo와 인터뷰가 고정한 커밋 (스펙 2026-10-24 §4.7). 다른 kind는 빈 목록,
         * 구버전 API의 null도 빈 목록으로 읽는다 — 워커는 null을 보지 않는다.
         */
        List<TaskRefRepo> refRepos,
        /**
         * DEPLOY 전용: 배포 전 레포 명령으로 먼저 빌드하라는 지시 (스펙 2026-10-28 §3). 꺼진 레포·다른 kind·구버전 API는
         * null — 워커는 지금처럼 Dockerfile 빌드만 한다.
         */
        PrebuildSpec prebuild,
        /**
         * IMPLEMENTATION(수정 회차 포함)·DESIGN 전용: 인터뷰에서 구현에 넘기기로 한 목업 파일(n 순, html 포함) (스펙 2026-11-03 §7).
         * 없는 작업·구버전 API의 null도 빈 목록으로 읽는다.
         */
        List<InterviewMockupFile> interviewMockups,
        /**
         * 계획이 가리켰지만 구현에 넘기지 않은 목업 번호(interview_mockup.n) — 구현 프롬프트가 "전달되지 않은 목업은 찾지 말고
         * 계획 글만 따르라"를 넣는 근거. 없으면(null 포함) 빈 목록.
         */
        List<Integer> excludedMockupNumbers,
        /** CLOSE 전용: 부분 패치 지시. null이면(모드가 REQUIRED가 아니거나 이미 만들었으면) 워커가 패치 단계를 건너뛴다 (스펙 2026-11-10). */
        ClosePatchSpec closePatch
) {
    public WorkerTaskResponse {
        if (refRepos == null) refRepos = List.of();
        if (interviewMockups == null) interviewMockups = List.of();
        if (excludedMockupNumbers == null) excludedMockupNumbers = List.of();
    }

    /**
     * 구현 claim에 싣는 인터뷰 목업 파일 1개. title·name은 업로드 때 정규화된 값이고 html은 에이전트가 만든 문서 그대로다.
     * html이 로그·예외 메시지에 새지 않도록 {@code toString()}은 길이만 보인다.
     */
    public record InterviewMockupFile(Integer n, String name, Integer version, String title, String html) {
        @Override
        public String toString() {
            return "InterviewMockupFile[n=" + n + ", name=" + name + ", version=" + version + ", title=" + title
                    + ", htmlLength=" + (html == null ? 0 : html.length()) + "]";
        }
    }

    /** 미리 빌드 지시. toolchain은 워커 설정의 도구 이름(null이면 워커 환경 그대로). */
    public record PrebuildSpec(String command, String artifact, String toolchain) {}

    /** 부분 패치(closePatch) 도입 전 34개 인자 정규 생성자 — 기존 팩토리·테스트 호환(closePatch=null). */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext,
                              CloseArtifactSpec closeArtifact, DbMcpGrant dbMcp, ImplementationSpec implementation,
                              List<TaskRefRepo> refRepos, PrebuildSpec prebuild,
                              List<InterviewMockupFile> interviewMockups, List<Integer> excludedMockupNumbers) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, closeArtifact, dbMcp, implementation, refRepos, prebuild,
                interviewMockups, excludedMockupNumbers, null);
    }

    /** 인터뷰 목업(interviewMockups·excludedMockupNumbers) 도입 전 32개 인자 정규 생성자 — 기존 팩토리·테스트 호환. */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext,
                              CloseArtifactSpec closeArtifact, DbMcpGrant dbMcp, ImplementationSpec implementation,
                              List<TaskRefRepo> refRepos, PrebuildSpec prebuild) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, closeArtifact, dbMcp, implementation, refRepos, prebuild,
                List.of(), List.of());
    }

    /** 미리 빌드(prebuild) 도입 전 31개 인자 정규 생성자 — 기존 팩토리·테스트 호환. */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext,
                              CloseArtifactSpec closeArtifact, DbMcpGrant dbMcp, ImplementationSpec implementation,
                              List<TaskRefRepo> refRepos) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, closeArtifact, dbMcp, implementation, refRepos, null);
    }

    /**
     * 구현 지시 블록. mode는 ImplementationMode 이름, timeoutMinutes는 claim 때 작업에 기록한 상한.
     * fix는 수정 회차가 열린 구현대기를 claim했을 때만 채운다(스펙 2026-11-03 §7) — 이 경우 mode·timeoutMinutes는 회차의
     * 구현 방식을 따르고(작업의 방식이 아니라), attempt는 작업의 시도 번호 그대로다. null이면 지금 구현과 같다.
     * taskType은 작업 타입 enum 이름(스펙 2026-11-03 §4) — 타입 없음·구버전 API는 null이고 워커는 모르는 값도 타입 없음으로 읽는다
     * ({@code TaskType.fromWireOrNull}). 구현 claim에서만 쓰는 값이라 최상위가 아니라 이 블록에 둔다(32칸 정규 생성자와
     * 호환 생성자들을 바꾸는 연쇄를 피한다).
     * db는 개발 DB 쓰기 재료(스펙 2026-11-03 §6.6·§6.8) — API가 구현 claim마다 채우고, 구버전 API·재료를 못 만든 호출은 null이라
     * 워커가 기본 경로·쓰기 없음으로 읽는다. taskType과 같은 이유로 이 블록에 둔다.
     */
    public record ImplementationSpec(String mode, Integer attempt, Integer timeoutMinutes, FixRoundSpec fix,
                                     String taskType, DbWriteSpec db) {
        /** 개발 DB 쓰기 도입 전 5인자 생성자 — 쓰기 재료 없음(db=null). 기존 팩토리·테스트 호환. */
        public ImplementationSpec(String mode, Integer attempt, Integer timeoutMinutes, FixRoundSpec fix,
                                  String taskType) {
            this(mode, attempt, timeoutMinutes, fix, taskType, null);
        }

        /** 작업 타입 도입 전 4인자 생성자 — 타입 없음(taskType=null), 쓰기 재료 없음. 기존 팩토리·테스트 호환. */
        public ImplementationSpec(String mode, Integer attempt, Integer timeoutMinutes, FixRoundSpec fix) {
            this(mode, attempt, timeoutMinutes, fix, null, null);
        }

        /** 수정 회차 도입 전 3인자 생성자 — 회차 없는 구현(fix=null), 타입 없음, 쓰기 재료 없음. 기존 팩토리·테스트 호환. */
        public ImplementationSpec(String mode, Integer attempt, Integer timeoutMinutes) {
            this(mode, attempt, timeoutMinutes, null, null, null);
        }
    }

    /**
     * 구현 claim의 개발 DB 쓰기 재료 (스펙 2026-11-03 §6.6·§6.8). scriptPath는 레포 안 상대 경로로 워커가 만드는 SQL 스크립트
     * 파일(카탈로그 폴더 또는 기본 `db/symphony` 아래 `task-{id}.sql`), migrationManaged는 대상 레포가 자동 마이그레이션 레포인지,
     * priorWrites는 이 작업의 이전 시도·회차가 반영했거나 승인 대기로 남긴 SQL 기록이 있는지(EXECUTED·DEFERRED).
     * 쓰기 가능 여부(연결별 writable)는 여기가 아니라 {@link DbMcpGrant}에 있다.
     */
    public record DbWriteSpec(String scriptPath, boolean migrationManaged, boolean priorWrites) {}

    /**
     * 수정 회차 구현 지시 — 같은 브랜치에서 이어 고친다(스펙 2026-11-03 §7). requestId는 task_fix_request.id, round는 승인 때
     * 확정한 회차 번호, attempt는 회차 안의 구현 시도 번호, body는 이번 회차의 수정 요청 본문, previousBodies는 앞선 승인
     * 회차의 요청 본문(오래된 것부터). headBranch·headSha는 이어 갈 브랜치와 기대하는 끝 커밋(원격이 앞서 있으면 워커가
     * 원격 끝을 쓴다). kind는 회차 종류(FIX|BASE_SYNC).
     */
    public record FixRoundSpec(Long requestId, int round, int attempt, String body, List<String> previousBodies,
                               String headBranch, String headSha, String kind) {
        /** 종류(kind) 도입 전 7개 인자 — 수정 요청(FIX) 회차. */
        public FixRoundSpec(Long requestId, int round, int attempt, String body, List<String> previousBodies,
                            String headBranch, String headSha) {
            this(requestId, round, attempt, body, previousBodies, headBranch, headSha, "FIX");
        }
    }

    /**
     * 구현 claim의 참조 repo 1개 — task.ref_repos 원소(RefRepo)의 스냅샷. githubRepo는 경로(GitHub owner/repo, GitLab 전체 경로),
     * repoHost는 gitUrl에서 판정한 "github" | "gitlab", sha는 인터뷰가 고정한 커밋. 폴더 경로는 싣지 않는다 —
     * worktree-root가 워커 설정이라 워커가 계산한다.
     */
    public record TaskRefRepo(Long catalogId, String alias, String githubRepo, String gitUrl, String repoHost,
                              String branch, String sha) {}

    /** 참조 repo(refRepos) 도입 전 30개 인자 정규 생성자 — 기존 팩토리·테스트 호환. */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext,
                              CloseArtifactSpec closeArtifact, DbMcpGrant dbMcp, ImplementationSpec implementation) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, closeArtifact, dbMcp, implementation, List.of());
    }

    /** 구현 지시(implementation) 도입 전 29개 인자 정규 생성자 — 기존 팩토리·테스트 호환. */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext,
                              CloseArtifactSpec closeArtifact, DbMcpGrant dbMcp) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, closeArtifact, dbMcp, null);
    }

    /**
     * CLOSE 필드 도입 전 23개 인자 생성자 — 종료 외 kind·기존 팩토리·테스트가 그대로 쓴다.
     * Jackson은 레코드의 정규 생성자(31개)를 쓰므로 역직렬화와 무관하다(WorkerResultRequest와 같은 방식).
     */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                null, null, null, null, null);
    }

    /** 산출물 지시(closeArtifact) 도입 전 27개 인자 생성자 — 기존 테스트·호출부 호환. */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, null);
    }

    /** DB MCP(dbMcp) 도입 전 28개 인자 생성자 — 기존 팩토리·테스트 호환. */
    public WorkerTaskResponse(Long id, String githubRepo, String githubBranch, String title, String description,
                              Kind kind, List<TaskMcpSpec> mcpsExtra, String analysisMarkdown, String subtasksJson,
                              String headBranch, String headSha, List<EnvVar> envVars, String model, String effort,
                              String designMarkdown, String mockupFilesJson, String feedbackHistoryJson,
                              String designUrl, String designSystemProjectId, String designOutputProjectId,
                              String gitUrl, String repoHost, String planMarkdown,
                              Integer prNumber, String prUrl, String closeProgress, CloseContext closeContext,
                              CloseArtifactSpec closeArtifact) {
        this(id, githubRepo, githubBranch, title, description, kind, mcpsExtra, analysisMarkdown, subtasksJson,
                headBranch, headSha, envVars, model, effort, designMarkdown, mockupFilesJson, feedbackHistoryJson,
                designUrl, designSystemProjectId, designOutputProjectId, gitUrl, repoHost, planMarkdown,
                prNumber, prUrl, closeProgress, closeContext, closeArtifact, null);
    }

    /** DB MCP 지시만 채운 사본. */
    public WorkerTaskResponse withDbMcp(DbMcpGrant grant) {
        return new WorkerTaskResponse(id, githubRepo, githubBranch, title, description, kind, mcpsExtra,
                analysisMarkdown, subtasksJson, headBranch, headSha, envVars, model, effort, designMarkdown,
                mockupFilesJson, feedbackHistoryJson, designUrl, designSystemProjectId, designOutputProjectId,
                gitUrl, repoHost, planMarkdown, prNumber, prUrl, closeProgress, closeContext, closeArtifact, grant,
                implementation, refRepos, prebuild, interviewMockups, excludedMockupNumbers, closePatch);
    }

    /** 인터뷰 목업 파일과 '전달되지 않은' 번호 두 칸만 바꾼 사본 — 둘은 함께 움직인다. */
    public WorkerTaskResponse withInterviewMockups(List<InterviewMockupFile> files, List<Integer> excludedNumbers) {
        return new WorkerTaskResponse(id, githubRepo, githubBranch, title, description, kind, mcpsExtra,
                analysisMarkdown, subtasksJson, headBranch, headSha, envVars, model, effort, designMarkdown,
                mockupFilesJson, feedbackHistoryJson, designUrl, designSystemProjectId, designOutputProjectId,
                gitUrl, repoHost, planMarkdown, prNumber, prUrl, closeProgress, closeContext, closeArtifact, dbMcp,
                implementation, refRepos, prebuild, files, excludedNumbers, closePatch);
    }

    /** 워커가 clone/폴더/MR에 쓰는 저장소 참조. 새 필드가 없는 페이로드는 GitHub로 복원된다. */
    public RepoRef repoRef() {
        return RepoRef.fromSnapshot(githubRepo, gitUrl);
    }

    private static String hostOf(Task t) {
        return RepoRef.fromSnapshot(t.getGithubRepo(), t.getGitUrl()).host();
    }

    public enum Kind { ANALYSIS, IMPLEMENTATION, DEPLOY, UNDEPLOY, DESIGN, CLOSE }

    public static WorkerTaskResponse forAnalysis(Task t) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.ANALYSIS,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                null, null, null, null, List.of(), t.getModel(), t.getEffort(),
                null, null, null, null, null, null, t.getGitUrl(), hostOf(t), null);
    }

    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a, TaskDesign d) {
        return forImplementation(t, a, d, null);
    }

    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a, TaskDesign d, String planMarkdown) {
        return forImplementation(t, a, d, planMarkdown, null);
    }

    /** fix가 있으면 수정 회차 구현 — 방식은 작업의 것(회차의 방식은 {@link #forImplementation(Task, TaskAnalysis, TaskDesign, String, FixRoundSpec, ImplementationMode)}). */
    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a, TaskDesign d, String planMarkdown,
                                                       FixRoundSpec fix) {
        return forImplementation(t, a, d, planMarkdown, fix, null);
    }

    /**
     * 수정 회차의 구현 방식은 작업이 아니라 요청 기록에 있다(스펙 2026-11-03 §6.2) — mode가 null이 아니면 그 방식을 싣는다.
     * 상한(timeoutMinutes)은 호출자가 claim 때 작업에 기록한 값을 그대로 쓴다.
     */
    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a, TaskDesign d, String planMarkdown,
                                                       FixRoundSpec fix, ImplementationMode mode) {
        return forImplementation(t, a, d, planMarkdown, fix, mode, null);
    }

    /**
     * 개발 DB 쓰기 재료(db)를 구현 블록에 싣는다 (스펙 2026-11-03 §6.6·§6.8). WorkerService.claim이 {@code dbWriteSpecFor(t)}를 넘긴다 —
     * null이면 재료 없음(위 6인자판·재료를 모르는 호출).
     */
    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a, TaskDesign d, String planMarkdown,
                                                       FixRoundSpec fix, ImplementationMode mode, DbWriteSpec db) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.IMPLEMENTATION,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(), a == null ? "[]" : a.getSubtasksJson(),
                null, null, List.of(), t.getModel(), t.getEffort(),
                d == null ? null : d.getDesignMarkdown(),
                d == null ? null : d.getMockupFilesJson(),
                null,
                d == null ? null : d.getDesignUrl(),
                null, null, t.getGitUrl(), hostOf(t), planMarkdown,
                null, null, null, null, null, null,
                implementationSpecOf(t, fix, mode, db), refReposOf(t));
    }

    private static ImplementationSpec implementationSpecOf(Task t, FixRoundSpec fix, ImplementationMode modeOverride,
                                                           DbWriteSpec db) {
        ImplementationMode mode = modeOverride != null ? modeOverride
                : t.getImplementationMode() == null ? ImplementationMode.DIRECT : t.getImplementationMode();
        String taskType = t.getTaskType() == null ? null : t.getTaskType().name();
        return new ImplementationSpec(mode.name(), t.getImplementationAttempt(), t.getImplementationTimeoutMinutes(),
                fix, taskType, db);
    }

    /** 작업 참조(task.ref_repos)를 그대로 싣는다 — 고정 커밋이 없거나 틀린 원소는 워커가 claude 실행 전에 구현실패로 끝낸다. */
    private static List<TaskRefRepo> refReposOf(Task t) {
        if (t.getRefRepos() == null) return List.of();
        return t.getRefRepos().stream()
                .map(r -> new TaskRefRepo(r.catalogId(), r.alias(), r.path(), r.gitUrl(),
                        RepoRef.fromSnapshot(r.path(), r.gitUrl()).host(), r.branch(), r.sha()))
                .toList();
    }

    public static WorkerTaskResponse forDeploy(Task t) {
        return forDeploy(t, null);
    }

    public static WorkerTaskResponse forDeploy(Task t, PrebuildSpec prebuild) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.DEPLOY, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(),
                t.getEnvVars() == null ? List.of() : List.copyOf(t.getEnvVars()), null, null,
                null, null, null, null, null, null, t.getGitUrl(), hostOf(t), null,
                null, null, null, null, null, null, null, List.of(), prebuild);
    }

    public static WorkerTaskResponse forUndeploy(Task t) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.UNDEPLOY, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(), List.of(), null, null,
                null, null, null, null, null, null, t.getGitUrl(), hostOf(t), null);
    }

    /** 디자인 구간 claim 페이로드. prev가 있으면(반려 재실행) 이전 디자인 + 피드백 이력 동봉. */
    public static WorkerTaskResponse forDesign(Task t, TaskAnalysis a, TaskDesign prev,
                                               String designSystemProjectId,
                                               String designOutputProjectId) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.DESIGN,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(), a == null ? "[]" : a.getSubtasksJson(),
                null, null, List.of(), t.getModel(), t.getEffort(),
                prev == null ? null : prev.getDesignMarkdown(),
                prev == null ? null : prev.getMockupFilesJson(),
                prev == null ? "[]" : prev.getFeedbackHistoryJson(),
                prev == null ? null : prev.getDesignUrl(),
                designSystemProjectId, designOutputProjectId, t.getGitUrl(), hostOf(t), null);
    }

    /** 종료 claim 페이로드. headSha는 병합을 고정할 구현 시점 커밋(확인한 코드만 병합). */
    public static WorkerTaskResponse forClose(Task t, CloseContext ctx) {
        return forClose(t, ctx, null);
    }

    public static WorkerTaskResponse forClose(Task t, CloseContext ctx, CloseArtifactSpec artifact) {
        return forClose(t, ctx, artifact, null);
    }

    public static WorkerTaskResponse forClose(Task t, CloseContext ctx, CloseArtifactSpec artifact,
                                              ClosePatchSpec patch) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.CLOSE, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(), List.of(), null, null,
                null, null, null, null, null, null, t.getGitUrl(), hostOf(t), null,
                t.getPrNumber(), t.getPrUrl(), t.getCloseProgress(), ctx, artifact, null, null, List.of(), null,
                List.of(), List.of(), patch);
    }
}
