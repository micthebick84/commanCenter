package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.entity.TaskType;
import org.springframework.http.HttpStatus;

/**
 * 작업 목록의 타입 필터 (스펙 2026-11-03 §4.3). 쿼리 파라미터 하나로 세 가지를 가른다.
 *  - 파라미터 없음·공백        → {@link #ANY}  (필터 없음)
 *  - {@value #NONE_WIRE}       → 타입 없는 작업만 (type == null)
 *  - TaskType enum 이름        → 그 타입만
 * "타입 없음"의 와이어 값은 설계가 정하지 않아 NONE으로 정했다(구현 계획 Task 3).
 * 저장소 쿼리는 any 불리언과 type을 따로 받는다 — JPQL이 null 파라미터와 "타입 없음"을 구분할 수 없어서다.
 */
public record TaskTypeFilter(boolean any, TaskType type) {

    /** "타입 없음"만 보는 필터의 쿼리 파라미터 값. */
    public static final String NONE_WIRE = "NONE";

    /** 필터 없음 — 타입이 있든 없든 모두. */
    public static final TaskTypeFilter ANY = new TaskTypeFilter(true, null);

    /** 타입이 없는 작업만. */
    private static final TaskTypeFilter NONE = new TaskTypeFilter(false, null);

    /**
     * 쿼리 파라미터 값 해석 — 앞뒤 공백을 자르고 대소문자는 구분한다(등록·승인 바디의 {@link TaskType#parseRequest}와 같은 규칙).
     * 모르는 값은 400.
     */
    public static TaskTypeFilter parse(String raw) {
        if (raw == null || raw.isBlank()) return ANY;
        String name = raw.trim();
        if (NONE_WIRE.equals(name)) return NONE;
        try {
            return new TaskTypeFilter(false, TaskType.valueOf(name));
        } catch (IllegalArgumentException e) {
            throw new TaskException(HttpStatus.BAD_REQUEST, "알 수 없는 작업 타입 필터: " + raw);
        }
    }
}
