package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 질문 세션 DB 접속정보 (스펙 2026-10-02 §4). passwordEnc = DbSecretCipher 암호문 — 평문은 claim 시에만 복호화한다.
 * 브라우저로는 DbConnectionDto.View만 내보낸다(암호문 필드 없음). toString도 암호문을 뺀다.
 */
@Entity
@Table(name = "db_connection", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DbConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DbConnectionScope scope;

    @Column(name = "repo_catalog_id", nullable = false)
    private Long repoCatalogId;

    @Column(name = "owner_user_id", length = 20)
    private String ownerUserId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "db_type", nullable = false, length = 20)
    private DbType dbType;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "database_name", nullable = false, length = 255)
    private String databaseName;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "password_enc", nullable = false, columnDefinition = "TEXT")
    private String passwordEnc;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by", length = 20)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static DbConnection create(DbConnectionScope scope, Long repoCatalogId, String ownerUserId, String name,
                                      DbType dbType, String host, int port, String databaseName, String username,
                                      String passwordEnc, String createdBy) {
        DbConnection c = new DbConnection();
        c.scope = scope;
        c.repoCatalogId = repoCatalogId;
        c.ownerUserId = ownerUserId;
        c.name = name;
        c.dbType = dbType;
        c.host = host;
        c.port = port;
        c.databaseName = databaseName;
        c.username = username;
        c.passwordEnc = passwordEnc;
        c.enabled = true;
        c.createdBy = createdBy;
        OffsetDateTime now = OffsetDateTime.now();
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public boolean isOwnedBy(String userId) {
        return scope == DbConnectionScope.USER && userId != null && userId.equals(ownerUserId);
    }

    @Override
    public String toString() {
        return "DbConnection{id=" + id + ", scope=" + scope + ", name=" + name + ", dbType=" + dbType
                + ", host=" + host + ":" + port + "/" + databaseName + "}";
    }
}
