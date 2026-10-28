package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 관리자 큐레이션 레포 카탈로그 엔트리.
 *
 * alias = 사용자에게 보일 한글 표시명. git_url = 정식 식별자(전체 Git URL).
 * owner_repo/host 는 업서트 시 RepoUrlParser 로 파싱해 박제.
 * 사용자는 작업/인터뷰 등록 시 활성(enabled=true) 엔트리만 선택 가능.
 */
@Entity
@Table(name = "repo_catalog", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepoCatalogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String alias;

    @Column(name = "git_url", nullable = false, unique = true, columnDefinition = "TEXT")
    private String gitUrl;

    @Column(nullable = false, length = 30)
    private String host;   // github | other

    @Column(name = "owner_repo", length = 255)
    private String ownerRepo;   // 비-GitHub면 null

    @Column(name = "default_branch", length = 255)
    private String defaultBranch;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    /** Claude Design 디자인 시스템 프로젝트 ID (입력). null이면 디자인 시 pull skip. */
    @Column(name = "design_system_project_id", length = 100)
    private String designSystemProjectId;

    /** Claude Design 목업 출력 프로젝트 ID. 워커가 최초 업로드 시 create_project 후 박제. */
    @Column(name = "design_output_project_id", length = 100)
    private String designOutputProjectId;

    /** 종료 때 배포 이미지에서 산출물을 꺼내 보관할지 (스펙 2026-10-14 §3). */
    @Column(name = "artifact_enabled", nullable = false)
    private boolean artifactEnabled;

    /** 이미지 안 산출물 경로. null이면 워커가 후보 폴더에서 하나일 때만 자동 선택. */
    @Column(name = "artifact_path", length = 500)
    private String artifactPath;

    /** 배포 전 워커가 레포 명령으로 먼저 빌드할지 (스펙 2026-10-28 §4). 꺼져 있어도 아래 값은 보존한다. */
    @Column(name = "prebuild_enabled", nullable = false)
    private boolean prebuildEnabled;

    /** 미리 빌드 명령(한 줄). */
    @Column(name = "prebuild_command", columnDefinition = "TEXT")
    private String prebuildCommand;

    /** 빌드 뒤 가져올 산출물 경로(레포 안 상대, 마지막 이름에만 *). */
    @Column(name = "prebuild_artifact", length = 500)
    private String prebuildArtifact;

    /** 워커 설정의 도구 이름(JDK·Maven 묶음). null이면 워커 환경 그대로. */
    @Column(name = "prebuild_toolchain", length = 50)
    private String prebuildToolchain;

    /** 종료 노트를 쓸 볼트 상대 폴더 (스펙 2026-10-28 close-note-folder). null이면 첫 종료 때 워커가 정해 기록한다. */
    @Column(name = "note_folder", length = 500)
    private String noteFolder;

    @Column(name = "created_by", length = 20)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static RepoCatalogEntry create(String alias, String gitUrl, String host, String ownerRepo,
                                          String defaultBranch, String description, String createdBy) {
        RepoCatalogEntry e = new RepoCatalogEntry();
        e.alias = alias;
        e.gitUrl = gitUrl;
        e.host = host;
        e.ownerRepo = ownerRepo;
        e.defaultBranch = defaultBranch;
        e.description = description;
        e.enabled = true;
        e.createdBy = createdBy;
        OffsetDateTime now = OffsetDateTime.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }
}
