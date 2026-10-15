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
 *  kind=DESIGN         → 목업 생성 + Claude Design 업로드 (디자인 시스템/출력 프로젝트 ID 동봉)
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
        ImplementationSpec implementation
) {
    /** 구현 지시 블록. mode는 ImplementationMode 이름, timeoutMinutes는 claim 때 작업에 기록한 상한. */
    public record ImplementationSpec(String mode, Integer attempt, Integer timeoutMinutes) {}

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
     * Jackson은 레코드의 정규 생성자(30개)를 쓰므로 역직렬화와 무관하다(WorkerResultRequest와 같은 방식).
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
                implementation);
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
                implementationSpecOf(t));
    }

    private static ImplementationSpec implementationSpecOf(Task t) {
        ImplementationMode mode = t.getImplementationMode() == null ? ImplementationMode.DIRECT : t.getImplementationMode();
        return new ImplementationSpec(mode.name(), t.getImplementationAttempt(), t.getImplementationTimeoutMinutes());
    }

    public static WorkerTaskResponse forDeploy(Task t) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.DEPLOY, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(),
                t.getEnvVars() == null ? List.of() : List.copyOf(t.getEnvVars()), null, null,
                null, null, null, null, null, null, t.getGitUrl(), hostOf(t), null);
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
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.CLOSE, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(), List.of(), null, null,
                null, null, null, null, null, null, t.getGitUrl(), hostOf(t), null,
                t.getPrNumber(), t.getPrUrl(), t.getCloseProgress(), ctx, artifact);
    }
}
