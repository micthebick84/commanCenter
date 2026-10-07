package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * GET /api/tasks/{id}/deploy-env 응답 — 배포 다이얼로그 미리 채움 (스펙 2026-10-07 §6.1).
 * 값은 평문(비밀 포함)이라 관리자 전용 엔드포인트에서만 내보낸다.
 *
 * @param templateCount  이 작업의 env 템플릿 항목 수
 * @param previousTaskId 값이나 KEY를 하나라도 직전 배포에서 가져왔을 때만 그 작업 id
 */
public record DeployEnvSuggestionResponse(List<Row> rows, int templateCount, Long previousTaskId) {

    /** KEY의 출처. */
    public enum Source { SAVED, TEMPLATE, PREVIOUS }

    /** @param valueFromPrevious 값을 직전 배포에서 가져왔는지 */
    public record Row(String key, String value, boolean secret, String description, boolean required,
                      Source source, boolean valueFromPrevious) {}
}
