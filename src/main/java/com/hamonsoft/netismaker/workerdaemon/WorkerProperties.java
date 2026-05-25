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
        // 분석 prompt
        String promptTemplate,
        Duration analysisTimeout,
        // 구현 단계
        String worktreeRoot,
        String branchPrefix,
        String gitUserName,
        String gitUserEmail,
        String implementationPromptTemplate,
        Duration implementationTimeout
) {
    public WorkerProperties {
        if (id == null || id.isBlank()) id = "mac-worker-1";
        if (apiBaseUrl == null) apiBaseUrl = "http://localhost:8090";
        if (pollIntervalSeconds <= 0) pollIntervalSeconds = 5;
        if (heartbeatIntervalSeconds <= 0) heartbeatIntervalSeconds = 10;
        if (claudeCliPath == null || claudeCliPath.isBlank()) claudeCliPath = "claude";
        if (analysisTimeout == null) analysisTimeout = Duration.ofMinutes(10);
        // 구현 단계 기본값
        if (worktreeRoot == null || worktreeRoot.isBlank()) {
            String home = System.getProperty("user.home");
            worktreeRoot = home + "/netis-maker/worktrees";
        }
        if (branchPrefix == null || branchPrefix.isBlank()) branchPrefix = "netismaker/";
        if (gitUserName == null || gitUserName.isBlank()) gitUserName = "netisMaker";
        if (gitUserEmail == null || gitUserEmail.isBlank()) gitUserEmail = "netismaker@hamonsoft.local";
        if (implementationTimeout == null) implementationTimeout = Duration.ofMinutes(45);
    }
}
