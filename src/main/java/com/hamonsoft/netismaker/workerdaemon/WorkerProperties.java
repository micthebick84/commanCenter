package com.hamonsoft.netismaker.workerdaemon;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "netis-maker.worker")
public record WorkerProperties(
        String id,
        String version,
        String apiBaseUrl,
        String apiKey,
        long pollIntervalSeconds,
        long heartbeatIntervalSeconds,
        String reposDir,
        String claudeCliPath,
        String githubPat,
        String promptTemplate,
        Duration analysisTimeout
) {
    public WorkerProperties {
        if (id == null || id.isBlank()) id = "mac-worker-1";
        if (apiBaseUrl == null) apiBaseUrl = "http://localhost:8090";
        if (pollIntervalSeconds <= 0) pollIntervalSeconds = 5;
        if (heartbeatIntervalSeconds <= 0) heartbeatIntervalSeconds = 10;
        if (claudeCliPath == null || claudeCliPath.isBlank()) claudeCliPath = "claude";
        if (analysisTimeout == null) analysisTimeout = Duration.ofMinutes(10);
    }
}
