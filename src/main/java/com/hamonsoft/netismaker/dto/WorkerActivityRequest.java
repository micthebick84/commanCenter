package com.hamonsoft.netismaker.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Body for POST /worker/interviews/{id}/activity — 활동 이벤트 배치 (스펙 §4.2, 최대 100건). */
public record WorkerActivityRequest(
        @NotEmpty @Size(max = 100) @Valid List<ActivityEvent> events
) {}
