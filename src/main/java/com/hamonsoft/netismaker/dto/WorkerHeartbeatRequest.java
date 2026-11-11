package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * claudeSessionOk·claudeAuthMethod·claudeAuthExpiresAt = claude 로그인 판정(스펙 2026-11-02 §3·§4) — 워커는
 * ClaudeAuthProbe, 인터뷰 서비스는 src/claudeAuth.ts. 방식은 ENV_TOKEN|CREDENTIALS|CLI, 판정 못 하면 셋 다 null.
 * lostReportCount = 재전송 대기 중인 결과 보고 수, deadReportCount = 재전송을 포기해 .dead.jsonl에 보관만 된 보고 수
 * (구버전 워커·인터뷰 서비스는 보내지 않는다 → 0).
 */
public record WorkerHeartbeatRequest(
        @NotBlank @Size(max = 50) String workerId,
        @Size(max = 100) String hostname,
        @Size(max = 50) String version,
        Boolean claudeSessionOk,
        @Size(max = 20) String vpnStatus,
        List<String> mcps,
        Integer lostReportCount,
        @Size(max = 20) String claudeAuthMethod,
        OffsetDateTime claudeAuthExpiresAt,
        Integer deadReportCount
) {}
