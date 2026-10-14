package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.Size;

/** POST /api/tasks/{id}/close/skip-artifact 바디 — 사유는 선택(상태 이력에 남는다). */
public record SkipArtifactRequest(@Size(max = 500) String reason) {}
