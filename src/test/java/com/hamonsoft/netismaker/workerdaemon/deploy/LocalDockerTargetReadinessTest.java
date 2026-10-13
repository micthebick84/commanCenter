package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 헬스체크 readiness 판정 — HTTP 응답 코드 + (404일 때만) 컨테이너 로그의 앱 기동 실패 흔적.
 * 2026-10-13 작업 10: Tomcat이 ROOT 웹앱 기동 실패 후에도 404로 응답해 배포완료로 처리됐다.
 */
class LocalDockerTargetReadinessTest {

    /** 작업 10 실패 컨테이너 로그(요약) — 리스너 기동 실패 후 Tomcat은 404로 계속 서빙한다. */
    private static final String TOMCAT_LISTENER_FAILED = """
            15:06:33.528 SEVERE [main] org.apache.catalina.core.StandardContext.startInternal One or more listeners failed to start. Full details will be found in the appropriate container log file
            15:06:33.530 SEVERE [main] org.apache.catalina.core.StandardContext.startInternal Context [] startup failed due to previous errors
            15:06:33.601 INFO [main] org.apache.catalina.startup.HostConfig.deployWAR Deployment of web application archive [/usr/local/tomcat/webapps/ROOT.war] has finished in [9,120] ms
            15:06:33.610 INFO [main] org.apache.catalina.startup.Catalina.start Server startup in [9180] milliseconds
            """;

    private static final String TOMCAT_HEALTHY = """
            15:14:05.897 INFO [main] org.apache.catalina.startup.HostConfig.deployWAR Deployment of web application archive [/usr/local/tomcat/webapps/ROOT.war] has finished in [7,576] ms
            15:14:05.917 INFO [main] org.apache.catalina.startup.Catalina.start Server startup in [7631] milliseconds
            """;

    private static Supplier<String> logs(String s, AtomicInteger calls) {
        return () -> { calls.incrementAndGet(); return s; };
    }

    @Test
    void no_http_response_is_not_ready_and_does_not_read_logs() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(LocalDockerTarget.judgeReadiness(-1, logs(TOMCAT_LISTENER_FAILED, calls)))
                .isEqualTo(LocalDockerTarget.Readiness.NOT_YET);
        assertThat(calls).hasValue(0);
    }

    @Test
    void non_404_responses_are_ready_without_reading_logs() {
        AtomicInteger calls = new AtomicInteger();
        for (int code : new int[]{200, 302, 401, 403, 500, 503}) {
            assertThat(LocalDockerTarget.judgeReadiness(code, logs(TOMCAT_LISTENER_FAILED, calls)))
                    .as("HTTP %d", code).isEqualTo(LocalDockerTarget.Readiness.READY);
        }
        assertThat(calls).hasValue(0);
    }

    @Test
    void http_404_with_app_startup_failure_in_logs_is_app_failed() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(LocalDockerTarget.judgeReadiness(404, logs(TOMCAT_LISTENER_FAILED, calls)))
                .isEqualTo(LocalDockerTarget.Readiness.APP_FAILED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void http_404_without_failure_marker_is_ready() {
        // 루트 경로를 매핑하지 않은 API 앱 등 — 기존처럼 서빙 중으로 본다
        assertThat(LocalDockerTarget.judgeReadiness(404, () -> TOMCAT_HEALTHY))
                .isEqualTo(LocalDockerTarget.Readiness.READY);
        assertThat(LocalDockerTarget.judgeReadiness(404, () -> null))
                .isEqualTo(LocalDockerTarget.Readiness.READY);
    }

    @Test
    void startup_failure_markers_cover_tomcat_spring_and_spring_boot() {
        assertThat(LocalDockerTarget.startupFailureMarker(TOMCAT_LISTENER_FAILED))
                .isEqualTo("One or more listeners failed to start");
        assertThat(LocalDockerTarget.startupFailureMarker(
                "SEVERE [main] ...StandardContext.startInternal One or more Filters failed to start."))
                .isEqualTo("One or more Filters failed to start");
        assertThat(LocalDockerTarget.startupFailureMarker(
                "SEVERE [main] ...StandardContext.startInternal Context [/app] startup failed due to previous errors"))
                .isEqualTo("startup failed due to previous errors");
        assertThat(LocalDockerTarget.startupFailureMarker(
                "SEVERE [main] ...HostConfig.deployWAR Error deploying web application archive [/usr/local/tomcat/webapps/ROOT.war]"))
                .isEqualTo("Error deploying web application archive");
        assertThat(LocalDockerTarget.startupFailureMarker(
                "ERROR org.springframework.web.context.ContextLoader - Context initialization failed"))
                .isEqualTo("Context initialization failed");
        assertThat(LocalDockerTarget.startupFailureMarker("""
                ***************************
                APPLICATION FAILED TO START
                ***************************
                """)).isEqualTo("APPLICATION FAILED TO START");
    }

    @Test
    void healthy_or_empty_logs_have_no_marker() {
        assertThat(LocalDockerTarget.startupFailureMarker(TOMCAT_HEALTHY)).isNull();
        assertThat(LocalDockerTarget.startupFailureMarker("")).isNull();
        assertThat(LocalDockerTarget.startupFailureMarker(null)).isNull();
    }
}
