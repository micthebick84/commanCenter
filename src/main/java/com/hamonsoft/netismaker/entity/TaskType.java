package com.hamonsoft.netismaker.entity;

import com.hamonsoft.netismaker.service.TaskException;
import org.springframework.http.HttpStatus;

/**
 * 작업 타입 (스펙 2026-11-03 §4.1). DB·API·워커·인터뷰 서비스가 enum 이름을 그대로 주고받는다.
 * 타입은 권한을 가르지 않는다 — 작업의 성격을 나타내고 인터뷰·구현 지시, 커밋·PR 제목 앞머리, 목록 표시에 쓰인다.
 * 타입 없음은 null이다(기존 작업, 옛 화면이 등록한 작업) — 모든 동작이 타입이 생기기 전과 같다.
 *  NEW_FEATURE    — 아예 없던 기능
 *  FEATURE_CHANGE — 잘 동작하던 기능을 바꿈
 *  BUG_FIX        — 화면 진입 시 오류 등 망가진 상황
 *  DB_WORK        — 테이블·데이터만 바꿈
 */
public enum TaskType {
    NEW_FEATURE("신규 개발", "feat"),
    FEATURE_CHANGE("기능 수정", "feat"),
    BUG_FIX("버그 픽스", "fix"),
    DB_WORK("DB 작업", "db");

    /** 타입 없음의 화면 이름. */
    public static final String NO_TYPE_LABEL = "타입 없음";
    /** 타입 없음의 커밋 앞머리 — 타입이 생기기 전부터 고정이던 값. */
    public static final String NO_TYPE_COMMIT_PREFIX = "feat";

    private final String label;
    private final String commitPrefix;

    TaskType(String label, String commitPrefix) {
        this.label = label;
        this.commitPrefix = commitPrefix;
    }

    /** 화면·이력에 쓰는 이름. */
    public String label() {
        return label;
    }

    /** 커밋 메시지·PR 제목 앞머리(콜론 제외). */
    public String commitPrefix() {
        return commitPrefix;
    }

    /**
     * 등록·승인 바디 값 해석 — null·공백이면 null(타입 없음 또는 유지), 앞뒤 공백을 자른 enum 이름(대소문자 구분)이 아니면 400.
     * 호출자가 null의 뜻을 정한다(등록 = 타입 없음, 승인 = 유지).
     */
    public static TaskType parseRequest(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "알 수 없는 작업 타입: " + raw);
        }
    }

    /** 워커·인터뷰 서비스가 claim 응답 값을 읽을 때 — null·공백·모르는 값(다른 버전 API)은 타입 없음으로 본다. */
    public static TaskType fromWireOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String labelOf(TaskType type) {
        return type == null ? NO_TYPE_LABEL : type.label;
    }

    public static String commitPrefixOf(TaskType type) {
        return type == null ? NO_TYPE_COMMIT_PREFIX : type.commitPrefix;
    }

    /** PR 제목 — 타입이 있으면 `<앞머리>: 제목`, 없으면 지금처럼 제목 그대로. */
    public static String prTitle(TaskType type, String title) {
        return type == null ? title : type.commitPrefix + ": " + title;
    }
}
