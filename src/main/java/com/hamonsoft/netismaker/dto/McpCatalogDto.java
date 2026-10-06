package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.McpCatalogEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public class McpCatalogDto {

    /** 사용자/관리자 조회 응답. */
    public record View(
            Long id,
            String name,
            String displayName,
            String url,
            String transport,
            String description,
            boolean enabled,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime lastCheckAt,
            String lastCheckStatus,    // HEALTHY | DEGRADED | DOWN | null(=UNKNOWN)
            String lastCheckError
    ) {
        public static View of(McpCatalogEntry e) {
            return new View(e.getId(), e.getName(), e.getDisplayName(), e.getUrl(),
                    e.getTransport(), e.getDescription(), e.isEnabled(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(),
                    e.getLastCheckAt(), e.getLastCheckStatus(), e.getLastCheckError());
        }
    }

    /** 관리자 등록/수정 요청. */
    public record UpsertRequest(
            @NotBlank
            @Pattern(regexp = "^[a-z0-9_-]+$",
                     message = "name은 소문자/숫자/하이픈/언더스코어만 (mcp__<name> 노출)")
            @Pattern(regexp = "^(?!db-).*$", message = "'db-'로 시작하는 이름은 DB 연결 서버용으로 예약되어 있습니다")
            @Size(max = 50)
            String name,

            @NotBlank
            @Size(max = 100)
            String displayName,

            @NotBlank
            @Pattern(regexp = "^https?://.+$",
                     message = "URL은 http(s)://로 시작해야 합니다")
            String url,

            @NotBlank
            @Pattern(regexp = "^(sse|http)$",
                     message = "transport는 'sse' 또는 'http'")
            String transport,

            String description,

            Boolean enabled
    ) {}
}
