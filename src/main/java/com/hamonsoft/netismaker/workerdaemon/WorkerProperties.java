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
        int resultReportMaxRetries,
        long resultReportBackoffMs,
        String reposDir,
        String deadLetterDir,
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
        Duration implementationTimeout,
        // 배포 단계
        Deploy deploy
) {
    /** 배포 설정. target=local 만 MVP 구현. */
    public record Deploy(
            String target,
            String portRange,           // 예: "19000-19099"
            int defaultContainerPort,
            String publicHost,
            Duration buildTimeout,
            String dockerfilePromptTemplate,
            int healthCheckSeconds,
            // HTTP readiness: 기동 후 이 시간(초) 안에 readinessPath가 HTTP 응답하면 즉시 배포완료로 본다.
            // 끝까지 응답이 없어도 컨테이너가 살아있으면 (비-HTTP 앱 가능성) liveness 기준으로 완료 처리.
            int readinessSeconds,
            String readinessPath,
            // GC 리퍼 (C2)
            Boolean gcEnabled,
            int gcIntervalMinutes,
            int gcOrphanGraceMinutes,
            int gcKeepImagesPerTask,
            // 배포 런타임 정합 (DeployReconcileJob): DB 배포완료 ↔ 실제 컨테이너 생존 대사
            Boolean reconcileEnabled,
            int reconcileIntervalSeconds,
            PublicAccess publicAccess
    ) {
        public Deploy {
            if (target == null || target.isBlank()) target = "local";
            if (portRange == null || portRange.isBlank()) portRange = "19000-19099";
            if (defaultContainerPort <= 0) defaultContainerPort = 8080;
            if (publicHost == null || publicHost.isBlank()) publicHost = "localhost";
            if (buildTimeout == null) buildTimeout = Duration.ofMinutes(10);
            if (healthCheckSeconds <= 0) healthCheckSeconds = 15;
            if (readinessSeconds <= 0) readinessSeconds = 40;
            if (readinessPath == null || readinessPath.isBlank()) readinessPath = "/";
            if (gcEnabled == null) gcEnabled = true;
            if (gcIntervalMinutes <= 0) gcIntervalMinutes = 60;
            if (gcOrphanGraceMinutes <= 0) gcOrphanGraceMinutes = 60;
            if (gcKeepImagesPerTask <= 0) gcKeepImagesPerTask = 1;
            if (reconcileEnabled == null) reconcileEnabled = true;
            if (reconcileIntervalSeconds <= 0) reconcileIntervalSeconds = 300;
            if (publicAccess == null) publicAccess = new PublicAccess(null, null, null);
        }
        public int portFrom() { return Integer.parseInt(portRange.split("-")[0].trim()); }
        public int portTo()   { return Integer.parseInt(portRange.split("-")[1].trim()); }

        /** 공개 배포 주소(Traefik 라우팅) 설정. */
        public record PublicAccess(Boolean enabled, String baseDomain, String network) {
            public PublicAccess {
                if (enabled == null) enabled = false;
                if (baseDomain == null || baseDomain.isBlank()) baseDomain = "micthebick.dev";
                if (network == null || network.isBlank()) network = "netis-deploy";
            }
        }
    }

    public WorkerProperties {
        if (id == null || id.isBlank()) id = "mac-worker-1";
        if (apiBaseUrl == null) apiBaseUrl = "http://localhost:8090";
        if (pollIntervalSeconds <= 0) pollIntervalSeconds = 5;
        if (heartbeatIntervalSeconds <= 0) heartbeatIntervalSeconds = 10;
        if (resultReportMaxRetries <= 0) resultReportMaxRetries = 5;
        if (resultReportBackoffMs <= 0) resultReportBackoffMs = 2000;
        if (deadLetterDir == null || deadLetterDir.isBlank()) {
            deadLetterDir = System.getProperty("user.home") + "/netis-maker/dead-letter";
        }
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
        if (deploy == null) deploy = new Deploy(null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, null, 0, null);
    }
}
