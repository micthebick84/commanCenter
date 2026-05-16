package com.hamonsoft.netismaker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record WorkerHeartbeatRequest(
        @NotBlank @Size(max = 50) String workerId,
        @Size(max = 100) String hostname,
        @Size(max = 50) String version,
        Boolean claudeSessionOk,
        @Size(max = 20) String vpnStatus,
        List<String> mcps
) {}
