package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.RepoCatalogEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public class RepoCatalogDto {

    /** 사용자/관리자 조회 응답. */
    public record View(
            Long id,
            String alias,
            String gitUrl,
            String host,
            String ownerRepo,
            String defaultBranch,
            String description,
            boolean enabled,
            String createdBy,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static View of(RepoCatalogEntry e) {
            return new View(e.getId(), e.getAlias(), e.getGitUrl(), e.getHost(), e.getOwnerRepo(),
                    e.getDefaultBranch(), e.getDescription(), e.isEnabled(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
        }
    }

    /** 관리자 등록/수정 요청. gitUrl 은 전체 URL / owner/repo / scp-ssh 모두 허용(서버가 정규화). */
    public record UpsertRequest(
            @NotBlank
            @Size(max = 100)
            String alias,

            @NotBlank
            @Size(max = 1000)
            String gitUrl,

            @Size(max = 255)
            String defaultBranch,

            String description,

            Boolean enabled
    ) {}

    /** 라이브 도달성 체크 결과(비영속 — DB에 헬스 저장 안 함). */
    public record CheckResult(boolean reachable, String defaultBranch, int branchCount, String error) {}
}
