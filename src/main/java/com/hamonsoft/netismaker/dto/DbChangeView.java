package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskDbWriteLog;

import java.time.OffsetDateTime;

/**
 * 작업의 DB 변경 기록 한 건 (스펙 2026-11-03 §6.5) — 화면(GET /api/tasks/{id}/db-changes)과 워커(GET /worker/tasks/{id}/db-changes)가
 * 같은 모양을 쓴다. 관리자 실행·실행 안 함의 응답 본문이기도 하다(갱신된 행).
 *
 * @param connectionId  연결을 지웠으면 null — connectionName은 기록 시점의 이름이라 그래도 보인다
 * @param classification EXECUTED·DEFERRED·DISMISSED·REJECTED·FAILED의 이름(문자열 — 모르는 값에도 화면·워커가 버티게)
 * @param executedAt    세션이 바로 실행한 행은 실행을 마친 시각(NULL이면 실행 중이거나 마무리 저장이 안 된 것),
 *                      승인 대기 행은 관리자가 실행하거나 실행 안 함으로 바꾼 시각
 * @param executedBy    관리자가 처리한 행의 사용자 id, 세션이 바로 실행한 행은 null
 */
public record DbChangeView(Long id, Long connectionId, String connectionName, int attempt, Long fixRequestId,
                           String sqlText, String classification, String reason, Long affectedRows, String error,
                           OffsetDateTime createdAt, OffsetDateTime executedAt, String executedBy) {

    public static DbChangeView of(TaskDbWriteLog l) {
        return new DbChangeView(l.getId(), l.getConnectionId(), l.getConnectionName(), l.getAttempt(),
                l.getFixRequestId(), l.getSqlText(),
                l.getClassification() == null ? null : l.getClassification().name(),
                l.getReason(), l.getAffectedRows(), l.getError(), l.getCreatedAt(), l.getExecutedAt(),
                l.getExecutedBy());
    }

    /** SQL 전문·오류·사유는 싣지 않는다(로그에 새지 않게). */
    @Override
    public String toString() {
        return "DbChangeView{id=" + id + ", connectionId=" + connectionId + ", attempt=" + attempt
                + ", classification=" + classification + "}";
    }
}
