package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SSE event:activity 페이로드의 원소 — 워커가 중계하는 에이전트 진행 활동 1건.
 * type: tool(도구 실행, label/detail) | text(내레이션 델타, content) | thinking(추론 델타, content).
 * transient — DB 미저장·replay 없음. 확정 내용은 question 턴이 대체한다.
 */
public record ActivityEvent(
        long seq,
        @NotBlank @Size(max = 16) String type,
        @Size(max = 100) String label,
        @Size(max = 200) String detail,
        @Size(max = 4096) String content
) {}
