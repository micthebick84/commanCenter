package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 요청 게시판 요청 (스펙 2026-10-19 §5). 읽음 판단은 시각 4개로 한다(§5.4).
 */
@Entity
@Table(name = "request_post", schema = "com")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RequestPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RequestKind kind;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** REPO일 때만, 선택. */
    @Column(name = "repo_hint", length = 500)
    private String repoHint;

    @Column(nullable = false, length = 16)
    private RequestStatus status;

    @Column(name = "requester_id", nullable = false, length = 20)
    private String requesterId;

    /** 관리자가 레포를 등록하면 연결되는 카탈로그 항목. 카탈로그 삭제 시 null. */
    @Column(name = "repo_catalog_id")
    private Long repoCatalogId;

    /** 등록 당시 스냅샷(카탈로그 삭제 후에도 표시). */
    @Column(name = "repo_git_url", length = 1000)
    private String repoGitUrl;

    @Column(name = "last_requester_activity_at", nullable = false)
    private OffsetDateTime lastRequesterActivityAt;

    @Column(name = "last_admin_activity_at")
    private OffsetDateTime lastAdminActivityAt;

    @Column(name = "requester_read_at", nullable = false)
    private OffsetDateTime requesterReadAt;

    @Column(name = "admin_read_at")
    private OffsetDateTime adminReadAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static RequestPost create(RequestKind kind, String title, String content,
                                     String repoHint, String requesterId) {
        RequestPost p = new RequestPost();
        p.kind = kind;
        p.title = title;
        p.content = content;
        p.repoHint = kind == RequestKind.REPO ? repoHint : null;
        p.status = RequestStatus.RECEIVED;
        p.requesterId = requesterId;
        OffsetDateTime now = OffsetDateTime.now();
        p.createdAt = now;
        p.updatedAt = now;
        p.lastRequesterActivityAt = now;
        p.requesterReadAt = now;
        return p;
    }

    public boolean isOwnedBy(String userId) {
        return requesterId != null && requesterId.equals(userId);
    }

    /** 요청자의 활동(생성·수정·답글). 자기 활동은 읽은 것으로 본다. */
    public void touchRequester(OffsetDateTime now) {
        this.lastRequesterActivityAt = now;
        this.updatedAt = now;
        this.requesterReadAt = now;
    }

    /** 관리자의 활동(답글·상태 변경·레포 등록). 관리자 읽음 시각은 하나를 공유한다. */
    public void touchAdmin(OffsetDateTime now) {
        this.lastAdminActivityAt = now;
        this.updatedAt = now;
        this.adminReadAt = now;
    }

    /** 상세 조회 시 열람자 쪽 읽음 시각을 갱신한다. 관리자가 자기 요청을 보면 둘 다 true. */
    public void markRead(boolean asRequester, boolean asAdmin, OffsetDateTime now) {
        if (asRequester) this.requesterReadAt = now;
        if (asAdmin) this.adminReadAt = now;
    }

    public boolean unreadFor(boolean asAdmin) {
        if (asAdmin) {
            return adminReadAt == null || lastRequesterActivityAt.isAfter(adminReadAt);
        }
        return lastAdminActivityAt != null && lastAdminActivityAt.isAfter(requesterReadAt);
    }
}
