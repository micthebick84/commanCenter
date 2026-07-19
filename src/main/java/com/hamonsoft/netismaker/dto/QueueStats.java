package com.hamonsoft.netismaker.dto;

public record QueueStats(
        long pending,
        long inProgress,
        long awaitingApproval,
        long approved,
        long implementing,
        long prCreated,
        long implementationFailed,
        long failed,
        long deployPending,
        long deploying,
        long deployed,
        long deployFailed,
        long deployLost,
        long undeployPending,
        long undeploying,
        Double avgDurationMs
) {}
