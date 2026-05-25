package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;

import java.util.List;

/**
 * 워커가 작업을 claim했을 때 받는 페이로드.
 *
 *  kind=ANALYSIS  → 분석 prompt 실행 (analysisMarkdown/subtasksJson은 null)
 *  kind=IMPLEMENTATION → worktree에서 구현 prompt 실행 (분석 산출물 동봉, 컨텍스트로 사용)
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
        String subtasksJson
) {
    public enum Kind { ANALYSIS, IMPLEMENTATION }

    public static WorkerTaskResponse forAnalysis(Task t) {
        return new WorkerTaskResponse(
                t.getId(),
                t.getGithubRepo(),
                t.getGithubBranch(),
                t.getTitle(),
                t.getDescription(),
                Kind.ANALYSIS,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                null,
                null
        );
    }

    public static WorkerTaskResponse forImplementation(Task t, TaskAnalysis a) {
        return new WorkerTaskResponse(
                t.getId(),
                t.getGithubRepo(),
                t.getGithubBranch(),
                t.getTitle(),
                t.getDescription(),
                Kind.IMPLEMENTATION,
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra()),
                a == null ? "" : a.getMarkdownResult(),
                a == null ? "[]" : a.getSubtasksJson()
        );
    }
}
