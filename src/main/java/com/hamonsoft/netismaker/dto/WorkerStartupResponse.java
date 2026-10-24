package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;

import java.util.List;

/**
 * 워커 기동 정합 응답 — 회수한 작업(전·후 상태)과 pending이라 건너뛴 작업 id.
 */
public record WorkerStartupResponse(List<Recovered> recovered, List<Long> skipped) {

    public record Recovered(Long taskId, TaskStatus from, TaskStatus to) {
    }
}
