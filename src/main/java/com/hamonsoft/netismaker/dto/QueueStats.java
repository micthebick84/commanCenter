package com.hamonsoft.netismaker.dto;

public record QueueStats(
        long pending,
        long inProgress,
        long awaitingApproval,
        long failed,
        Double avgDurationMs
) {}
