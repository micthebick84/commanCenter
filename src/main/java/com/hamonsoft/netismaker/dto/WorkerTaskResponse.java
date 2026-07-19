package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskDesign;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;

import java.util.List;

/**
 * 워커가 작업을 claim했을 때 받는 페이로드.
 *
 *  kind=ANALYSIS       → 분석 prompt 실행 (analysis/headBranch/headSha null)
 *  kind=IMPLEMENTATION → worktree에서 구현 prompt 실행 (분석 산출물 동봉)
 *  kind=DEPLOY         → head 브랜치(headBranch@headSha)를 빌드해 docker 배포 (envVars 주입)
 *  kind=UNDEPLOY       → 컨테이너 netis-task-{id} 중지 (id만 사용)
 *  kind=DESIGN         → 목업 생성 + Claude Design 업로드 (디자인 시스템/출력 프로젝트 ID 동봉)
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
        String designOutputProjectId
) {
    public enum Kind { ANALYSIS, IMPLEMENTATION, DEPLOY, UNDEPLOY, DESIGN }

    public static WorkerTaskResponse forAnalysis(Task t) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.ANALYSIS,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                null, null, null, null, List.of(), t.getModel(), t.getEffort(),
                null, null, null, null, null, null);
    }

    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a, TaskDesign d) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.IMPLEMENTATION,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(), a == null ? "[]" : a.getSubtasksJson(),
                null, null, List.of(), t.getModel(), t.getEffort(),
                d == null ? null : d.getDesignMarkdown(),
                d == null ? null : d.getMockupFilesJson(),
                null,
                d == null ? null : d.getDesignUrl(),
                null, null);
    }

    public static WorkerTaskResponse forDeploy(Task t) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.DEPLOY, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(),
                t.getEnvVars() == null ? List.of() : List.copyOf(t.getEnvVars()), null, null,
                null, null, null, null, null, null);
    }

    public static WorkerTaskResponse forUndeploy(Task t) {
        return new WorkerTaskResponse(t.getId(), t.getGithubRepo(), t.getGithubBranch(),
                t.getTitle(), t.getDescription(), Kind.UNDEPLOY, List.of(), null, null,
                t.getHeadBranch(), t.getHeadSha(), List.of(), null, null,
                null, null, null, null, null, null);
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
                designSystemProjectId, designOutputProjectId);
    }
}
