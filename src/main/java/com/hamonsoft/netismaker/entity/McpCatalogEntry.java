package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 관리자 큐레이션 SSE MCP 카탈로그 엔트리.
 *
 * name = mcp__<name> 형태로 claude에 노출. URL은 SSE 엔드포인트.
 * 사용자는 작업 등록 시 활성(enabled=true) 엔트리만 선택 가능.
 */
@Entity
@Table(name = "mcp_catalog", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class McpCatalogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(nullable = false, length = 20)
    private String transport;  // 현재 'sse' 만

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by", length = 20)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** 마지막 헬스 체크 시각. null이면 아직 체크 안 됨 (UNKNOWN). */
    @Column(name = "last_check_at")
    private OffsetDateTime lastCheckAt;

    /** HEALTHY | DEGRADED | DOWN. null = UNKNOWN. */
    @Column(name = "last_check_status", length = 20)
    private String lastCheckStatus;

    /** 실패 시 에러 메시지. HEALTHY면 null. */
    @Column(name = "last_check_error", columnDefinition = "TEXT")
    private String lastCheckError;

    public static McpCatalogEntry create(String name, String displayName, String url,
                                         String transport, String description, String createdBy) {
        McpCatalogEntry e = new McpCatalogEntry();
        e.name = name;
        e.displayName = displayName;
        e.url = url;
        e.transport = transport == null || transport.isBlank() ? "sse" : transport;
        e.description = description;
        e.enabled = true;
        e.createdBy = createdBy;
        OffsetDateTime now = OffsetDateTime.now();
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }
}
