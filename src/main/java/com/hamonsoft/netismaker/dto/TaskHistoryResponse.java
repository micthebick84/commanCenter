package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;

import java.time.OffsetDateTime;

/**
 * 작업 상태 변경 이력 1건 (스펙 2026-09-06 §6). fromStatus/toStatus는 영문 enum 이름(프론트 로직·색),
 * fromLabel/toLabel은 DB에 저장된 한글 dbValue 그대로(표시). 과거에 폐기된 상태값이 남아 있으면 이름은 null, 라벨은 유지.
 */
public record TaskHistoryResponse(
        String fromStatus,
        String toStatus,
        String fromLabel,
        String toLabel,
        String actorType,
        String actorId,
        String reason,
        OffsetDateTime at
) {
    public static TaskHistoryResponse of(TaskStatusHistory h) {
        return new TaskHistoryResponse(nameOf(h.getFromStatus()), nameOf(h.getToStatus()),
                h.getFromStatus(), h.getToStatus(), h.getActorType(), h.getActorId(), h.getReason(), h.getAt());
    }

    private static String nameOf(String dbValue) {
        if (dbValue == null) return null;
        try {
            return TaskStatus.fromDb(dbValue).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
