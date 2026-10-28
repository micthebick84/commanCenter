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
            String artifactPath,
            boolean prebuildEnabled,
            String prebuildCommand,
            String prebuildArtifact,
            String prebuildToolchain,
            String noteFolder
    ) {
        public static View of(RepoCatalogEntry e) {
            return new View(e.getId(), e.getAlias(), e.getGitUrl(), e.getHost(), e.getOwnerRepo(),
                    e.getDefaultBranch(), e.getDescription(), e.isEnabled(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(), e.isArtifactEnabled(), e.getArtifactPath(),
                    e.isPrebuildEnabled(), e.getPrebuildCommand(), e.getPrebuildArtifact(), e.getPrebuildToolchain(),
                    e.getNoteFolder());
        }

        /** host/ownerRepo를 저장값 대신 현재 설정으로 재해석한 값으로 내보낸다(스펙 §3.3). */
        public static View of(RepoCatalogEntry e, String host, String ownerRepo) {
            return new View(e.getId(), e.getAlias(), e.getGitUrl(), host, ownerRepo,
                    e.getDefaultBranch(), e.getDescription(), e.isEnabled(),
                    e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(), e.isArtifactEnabled(), e.getArtifactPath(),
                    e.isPrebuildEnabled(), e.getPrebuildCommand(), e.getPrebuildArtifact(), e.getPrebuildToolchain(),
                    e.getNoteFolder());
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
            String artifactPath,

            /** 배포 전 미리 빌드 (스펙 2026-10-28 §4). null이면 미리 빌드 4개 값을 바꾸지 않는다. */
            Boolean prebuildEnabled,

            @Size(max = 4000)
            String prebuildCommand,

            @Size(max = 500)
            String prebuildArtifact,

            @Size(max = 50)
            String prebuildToolchain,

            /** 종료 노트 폴더 (스펙 2026-10-28 close-note-folder §3-1). 수정 때 null이면 그대로, 공백이면 비움. */
            @Size(max = 500)
            String noteFolder
    ) {
        /** 노트 폴더 도입 전 11개 인자 — 기존 호출부·테스트 호환(노트 폴더 유지). */
        public UpsertRequest(String alias, String gitUrl, String defaultBranch, String description, Boolean enabled,
                             Boolean artifactEnabled, String artifactPath, Boolean prebuildEnabled,
                             String prebuildCommand, String prebuildArtifact, String prebuildToolchain) {
            this(alias, gitUrl, defaultBranch, description, enabled, artifactEnabled, artifactPath,
                    prebuildEnabled, prebuildCommand, prebuildArtifact, prebuildToolchain, null);
        }

        /** 미리 빌드 설정 도입 전 7개 인자 — 기존 호출부·테스트 호환(미리 빌드 설정 유지). */
        public UpsertRequest(String alias, String gitUrl, String defaultBranch, String description, Boolean enabled,
                             Boolean artifactEnabled, String artifactPath) {
            this(alias, gitUrl, defaultBranch, description, enabled, artifactEnabled, artifactPath,
                    null, null, null, null);
        }

        /** 산출물 설정 도입 전 5개 인자 — 기존 호출부·테스트 호환(산출물·미리 빌드 설정 유지). */
        public UpsertRequest(String alias, String gitUrl, String defaultBranch, String description, Boolean enabled) {
            this(alias, gitUrl, defaultBranch, description, enabled, null, null);
        }
    }

    /** 라이브 도달성 체크 결과(비영속 — DB에 헬스 저장 안 함). */
    public record CheckResult(boolean reachable, String defaultBranch, int branchCount, String error) {}
}
