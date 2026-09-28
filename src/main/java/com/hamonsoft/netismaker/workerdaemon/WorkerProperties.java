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
        // dead-letter 재전송(DeadLetterReplayJob): 엔트리별 최대 재전송 시도 횟수 (소진 시 .dead.jsonl 보존)
        // 재전송 주기는 netis-maker.worker.replay-interval-seconds (스케줄 SpEL 기본 60초, 필드 아님)
        int replayMaxAttempts,
        String reposDir,
        String deadLetterDir,
        String claudeCliPath,
        String githubPat,
        // 사내 GitLab 토큰(api + read_repository + write_repository). clone/push + Draft MR 생성.
        String gitlabToken,
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
        // 디자인 단계
        String designPromptTemplate,
        Duration designTimeout,
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
            if (publicAccess == null) publicAccess = new PublicAccess(null, null, null, null, null);
        }
        public int portFrom() { return Integer.parseInt(portRange.split("-")[0].trim()); }
        public int portTo()   { return Integer.parseInt(portRange.split("-")[1].trim()); }

        /** 공개 배포 주소(Traefik 라우팅) 설정. */
        public record PublicAccess(Boolean enabled, String baseDomain, String network,
                                   // 슬러그 접미사: task-{id}{slugSuffix}. 같은 base-domain을 쓰는 다른 스택과 이름 충돌 회피용(예 "-win").
                                   String slugSuffix,
                                   Dns dns) {
            public PublicAccess {
                if (enabled == null) enabled = false;
                if (baseDomain == null || baseDomain.isBlank()) baseDomain = "micthebick.dev";
                // taskIdOf가 호스트명을 소문자로 비교하므로 여기서도 맞춰둔다 — 대문자 설정값이면 매칭이 어긋난다.
                baseDomain = baseDomain.toLowerCase(java.util.Locale.ROOT);
                if (network == null || network.isBlank()) network = "netis-deploy";
                slugSuffix = slugSuffix == null ? "" : slugSuffix.trim();
                // DNS 라벨로 안전한 값만 — 잘못된 값은 배포 때가 아니라 부팅 때 드러낸다.
                if (!slugSuffix.matches("(-[a-z0-9]+)*")) {
                    throw new IllegalArgumentException(
                            "deploy.public-access.slug-suffix는 '-소문자영숫자' 형식이어야 한다: '" + slugSuffix + "'");
                }
                if (dns == null) dns = new Dns(null, null, null, null);
            }

            /**
             * 배포별 DNS 레코드 자동 등록. provider=none(기본)이면 사용하지 않는다.
             * cloudflare: 배포마다 {slug}.{baseDomain} CNAME → {tunnelId}.cfargotunnel.com.
             */
            public record Dns(String provider, String apiToken, String zoneId, String tunnelId) {
                public Dns {
                    provider = (provider == null || provider.isBlank())
                            ? "none" : provider.trim().toLowerCase(java.util.Locale.ROOT);
                    // 오타(예: "cloudfalre")를 "none"으로 조용히 묵살하지 않는다 — 알아채지 못하면 DNS가 아예 등록되지 않는다.
                    if (!"none".equals(provider) && !"cloudflare".equals(provider)) {
                        throw new IllegalArgumentException(
                                "DEPLOY_PUBLIC_DNS_PROVIDER는 none|cloudflare만 허용한다: '" + provider + "'");
                    }
                }

                public boolean cloudflare() {
                    return "cloudflare".equals(provider);
                }

                /** 토큰은 절대 출력하지 않는다(설정 덤프·로그 대비). */
                @Override
                public String toString() {
                    return "Dns[provider=" + provider + ", apiToken="
                            + (apiToken == null || apiToken.isBlank() ? "(없음)" : "•••")
                            + ", zoneId=" + zoneId + ", tunnelId=" + tunnelId + "]";
                }
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
        if (replayMaxAttempts <= 0) replayMaxAttempts = 3;
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
        if (designTimeout == null) designTimeout = Duration.ofMinutes(30);
        if (deploy == null) deploy = new Deploy(null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, null, 0, null);
    }
}
