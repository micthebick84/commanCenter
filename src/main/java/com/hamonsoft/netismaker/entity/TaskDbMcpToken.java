package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** 디자인·구현 단계 DB MCP 작업 토큰 (스펙 2026-10-14 §6.2). 해시만 저장. */
@Entity
@Table(name = "task_db_mcp_token", schema = "com")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskDbMcpToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "stage", nullable = false, length = 32)
    private String stage;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public static TaskDbMcpToken issue(Long taskId, String tokenHash, String stage,
                                       OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        TaskDbMcpToken t = new TaskDbMcpToken();
        t.taskId = taskId;
        t.tokenHash = tokenHash;
        t.stage = stage;
        t.issuedAt = issuedAt;
        t.expiresAt = expiresAt;
        return t;
    }
}
