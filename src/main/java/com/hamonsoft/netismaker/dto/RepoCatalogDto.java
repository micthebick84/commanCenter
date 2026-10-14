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
            OffsetDateTime updatedAt,
            boolean artifactEnabled,
            String artifactPath
    ) {
        public static View of(RepoCatalogEntry e) {
            return new View(e.getId(), e.getAlias(), e.getGitUrl(), e.getHost(), e.getOwnerRepo(),
                    e.getDefaultBranch(), e.getDescription(), e.isEnabled(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(), e.isArtifactEnabled(), e.getArtifactPath());
        }

        /** host/ownerRepo를 저장값 대신 현재 설정으로 재해석한 값으로 내보낸다(스펙 §3.3). */
        public static View of(RepoCatalogEntry e, String host, String ownerRepo) {
            return new View(e.getId(), e.getAlias(), e.getGitUrl(), host, ownerRepo,
                    e.getDefaultBranch(), e.getDescription(), e.isEnabled(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(), e.isArtifactEnabled(), e.getArtifactPath());
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

            Boolean enabled,

            /** 종료 산출물 보관 (스펙 2026-10-14 §3). null이면 산출물 설정(경로 포함)을 바꾸지 않는다. */
            Boolean artifactEnabled,

            @Size(max = 500)
            String artifactPath
    ) {
        /** 산출물 설정 도입 전 5개 인자 — 기존 호출부·테스트 호환(산출물 설정 유지). */
        public UpsertRequest(String alias, String gitUrl, String defaultBranch, String description, Boolean enabled) {
            this(alias, gitUrl, defaultBranch, description, enabled, null, null);
        }
    }

    /** 라이브 도달성 체크 결과(비영속 — DB에 헬스 저장 안 함). */
    public record CheckResult(boolean reachable, String defaultBranch, int branchCount, String error) {}
}
