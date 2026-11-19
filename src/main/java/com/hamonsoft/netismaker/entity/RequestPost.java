package com.hamonsoft.netismaker.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 요청 게시판 요청 (스펙 2026-10-19 §5). 읽음 판단은 시각 4개로 한다(§5.4).
 * {@code @DynamicUpdate}: 읽음·활동 시각 갱신이 동시에 일어난 관리자의 상태·레포 변경을 옛 값으로 덮지 않도록
 * 바뀐 컬럼만 UPDATE한다.
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
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

    /** 개발 요청의 이슈 유형(스펙 2026-11-19 §6). 다른 종류는 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", length = 20)
    private RequestIssueType issueType;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private RequestPriority priority;

    /** 희망 완료일. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** 개발 요청의 대상 레포 카탈로그 항목. 카탈로그 삭제 시 null. */
    @Column(name = "target_repo_catalog_id")
    private Long targetRepoCatalogId;

    /** 이 요청으로 등록한 작업 id(기록용, FK 없음). */
    @Column(name = "task_id")
    private Long taskId;

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

    /** 개발 요청(DEV) 등록용. repoHint는 받지 않는다. */
    public static RequestPost createDev(String title, String content, RequestIssueType issueType,
                                        RequestPriority priority, LocalDate dueDate,
                                        Long targetRepoCatalogId, String requesterId) {
        RequestPost p = create(RequestKind.DEV, title, content, null, requesterId);
        p.issueType = issueType;
        p.priority = priority;
        p.dueDate = dueDate;
        p.targetRepoCatalogId = targetRepoCatalogId;
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

    /**
     * 요청자도 관리자도 아닌 사용자의 활동(개발 요청 공개 답글). 요청자와 관리자 양쪽에 새 활동으로 보이게
     * 활동 시각만 올리고, 읽음 시각은 건드리지 않는다.
     */
    public void touchByOther(OffsetDateTime now) {
        this.lastRequesterActivityAt = now;
        this.lastAdminActivityAt = now;
        this.updatedAt = now;
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
