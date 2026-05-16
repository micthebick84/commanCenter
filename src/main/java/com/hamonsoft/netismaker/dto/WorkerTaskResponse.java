package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskMcpSpec;

import java.util.List;

/**
 * 워커가 작업을 claim했을 때 받는 페이로드.
 * 분석에 필요한 최소 필드 + 작업 등록 시 선택된 추가 MCP 스펙.
 */
public record WorkerTaskResponse(
        Long id,
        String githubRepo,
        String githubBranch,
        String title,
        String description,
        List<TaskMcpSpec> mcpsExtra
) {
    public static WorkerTaskResponse of(Task t) {
        return new WorkerTaskResponse(
                t.getId(),
                t.getGithubRepo(),
                t.getGithubBranch(),
                t.getTitle(),
                t.getDescription(),
                t.getMcpsExtra() == null ? List.of() : List.copyOf(t.getMcpsExtra())
        );
    }
}
