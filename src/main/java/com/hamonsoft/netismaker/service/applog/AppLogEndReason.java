package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.config.AppLogProperties;

/**
 * 앱 로그 스트림이 끝난 사유 — SSE `end {reason, message}`로 나간다 (스펙 2026-11-03 §4).
 * 거절(꺼짐·허용 상태 아님·동시 상한)도 HTTP 상태가 아니라 200 응답 안의 `end`로 보낸다 — EventSource는 상태 코드와
 * 본문을 읽지 못한다. 화면은 {@link #wire()}로 다시 연결할지를 가른다(Task 9).
 */
public enum AppLogEndReason {

    NOT_ALLOWED_STATUS("not-allowed-status", "지금 상태에서는 앱 로그를 볼 수 없습니다"),
    TOO_MANY("too-many", "동시에 볼 수 있는 앱 로그 수를 넘었습니다"),
    DISABLED("disabled", "앱 로그 기능이 꺼져 있습니다(docker 없음 또는 설정 꺼짐)"),
    NO_CONTAINER("no-container", "컨테이너가 없어 로그를 볼 수 없습니다"),
    DOCKER_ERROR("docker-error", "docker 로그를 읽지 못했습니다"),
    /** 문구에 설정값(분)이 들어가므로 {@link #message(AppLogProperties)}가 만든다. */
    TIME_LIMIT("time-limit", null),
    CONTAINER_STOPPED("container-stopped", "컨테이너가 멈췄습니다");

    private final String wire;
    private final String fixedMessage;

    AppLogEndReason(String wire, String fixedMessage) {
        this.wire = wire;
        this.fixedMessage = fixedMessage;
    }

    /** SSE `end`의 reason 값. */
    public String wire() {
        return wire;
    }

    /** SSE `end`의 message 값 — 화면에 그대로 보인다. */
    public String message(AppLogProperties p) {
        return this == TIME_LIMIT ? p.maxDurationMinutes() + "분이 지나 연결을 닫았습니다" : fixedMessage;
    }
}
