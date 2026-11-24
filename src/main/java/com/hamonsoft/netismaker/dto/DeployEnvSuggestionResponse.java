package com.hamonsoft.netismaker.dto;

import java.util.List;

/**
 * GET /api/tasks/{id}/deploy-env 응답 — 배포 다이얼로그 미리 채움 (스펙 2026-10-07 §6.1).
 * 값은 평문(비밀 포함)이라 관리자 전용 엔드포인트에서만 내보낸다.
 *
 * @param templateCount  이 작업의 env 템플릿 항목 수
 * @param previousTaskId 값이나 KEY, DB 선택을 하나라도 직전 배포에서 가져왔을 때만 그 작업 id
 * @param databases      카탈로그 배포 설정의 DB 자리별 선택지·선택 (배포 설정이 없으면 빈 목록)
 */
public record DeployEnvSuggestionResponse(List<Row> rows, int templateCount, Long previousTaskId,
                                          List<DbSlotView> databases) {

    public DeployEnvSuggestionResponse {
        databases = databases == null ? List.of() : databases;
    }

    public DeployEnvSuggestionResponse(List<Row> rows, int templateCount, Long previousTaskId) {
        this(rows, templateCount, previousTaskId, List.of());
    }

    /** KEY의 출처. CATALOG = 카탈로그 배포 설정이 정한 변수. SAVED·PREVIOUS는 DB 선택의 출처로도 쓴다. */
    public enum Source { SAVED, TEMPLATE, PREVIOUS, CATALOG }

    /**
     * @param valueFromPrevious 값을 직전 배포에서 가져왔는지
     * @param format            카탈로그 변수의 값 형식 안내 (없으면 null)
     */
    public record Row(String key, String value, boolean secret, String description, boolean required,
                      Source source, boolean valueFromPrevious, String format) {
        public Row(String key, String value, boolean secret, String description, boolean required,
                   Source source, boolean valueFromPrevious) {
            this(key, value, secret, description, required, source, valueFromPrevious, null);
        }
    }

    /** DB 자리 하나. selected가 null이면 미리 고른 것이 없다. */
    public record DbSlotView(String slot, String label, List<String> dbTypes, String targetSummary,
                             Selected selected, List<Choice> choices) {}

    /** connectionId가 null이면 "고르지 않음". from은 SAVED|PREVIOUS. */
    public record Selected(Long connectionId, Source from) {}

    /** 고를 수 있는 연결 — 비밀번호 칸 없음. scope는 REPO|USER. */
    public record Choice(long id, String name, String dbType, String scope, String host, int port, String database) {}
}
