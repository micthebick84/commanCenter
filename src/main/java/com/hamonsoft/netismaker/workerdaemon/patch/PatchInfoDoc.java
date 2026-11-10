package com.hamonsoft.netismaker.workerdaemon.patch;

import java.time.OffsetDateTime;
import java.util.List;

/** PATCH-INFO.txt 에 적을 내용. */
public record PatchInfoDoc(
        long taskId,
        String title,
        String repoAlias,
        String beforeSha,
        String afterSha,
        OffsetDateTime createdAt,
        String command,
        PatchDelta delta,
        List<String> sourceChanges) {
}
