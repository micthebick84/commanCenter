package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.RequestIssueType;
import com.hamonsoft.netismaker.entity.RequestKind;
import com.hamonsoft.netismaker.entity.RequestPost;
import com.hamonsoft.netismaker.entity.RequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RequestPostRepository extends JpaRepository<RequestPost, Long> {

    List<RequestPost> findByRequesterIdOrderByUpdatedAtDesc(String requesterId, Limit limit);

    long countByRequesterIdAndStatusIn(String requesterId, Collection<RequestStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RequestPost p where p.id = :id")
    Optional<RequestPost> findByIdForUpdate(@Param("id") Long id);

    /**
     * 관리자 목록. statuses에 든 요청에 더해, includeUnread면 상태와 무관하게
     * 관리자가 아직 못 본 요청(새 요청·요청자의 새 활동)도 포함한다.
     */
    @Query("""
            select p from RequestPost p
            where (:kind is null or p.kind = :kind)
              and (p.status in :statuses
                   or (:includeUnread = true
                       and (p.adminReadAt is null or p.lastRequesterActivityAt > p.adminReadAt)))
            order by p.updatedAt desc
            """)
    List<RequestPost> findForAdmin(@Param("kind") RequestKind kind,
                                   @Param("statuses") Collection<RequestStatus> statuses,
                                   @Param("includeUnread") boolean includeUnread,
                                   Pageable page);

    /**
     * 개발 요청 전원 공개 목록. anyStatus면 상태 조건을 건너뛴다(statuses는 비지 않은 자리표시 값이어야 한다).
     * 최신 활동순. kind는 호출자가 DEV로 고정해 넘긴다.
     */
    @Query("""
            select p from RequestPost p
            where p.kind = :kind
              and (:issueType is null or p.issueType = :issueType)
              and (:anyStatus = true or p.status in :statuses)
            order by p.updatedAt desc
            """)
    List<RequestPost> findDev(@Param("kind") RequestKind kind,
                              @Param("issueType") RequestIssueType issueType,
                              @Param("statuses") Collection<RequestStatus> statuses,
                              @Param("anyStatus") boolean anyStatus,
                              Pageable page);

    /** 관리자 주의 필요 건수: 접수이거나 관리자가 못 본 활동이 있는 요청. */
    @Query("""
            select count(p) from RequestPost p
            where p.status = com.hamonsoft.netismaker.entity.RequestStatus.RECEIVED
               or p.adminReadAt is null
               or p.lastRequesterActivityAt > p.adminReadAt
            """)
    long countAttention();

    long countByStatus(RequestStatus status);

    /** 첨부 정리 잡(AttachmentCleanupJob)용 경량 행 — id + 상태 + 마지막 갱신 시각. */
    interface AttachmentOwnerState {
        Long getId();

        RequestStatus getStatus();

        OffsetDateTime getUpdatedAt();
    }

    /** 행이 없는 id는 결과에서 빠진다(고아). */
    @Query("SELECT p.id AS id, p.status AS status, p.updatedAt AS updatedAt FROM RequestPost p WHERE p.id IN :ids")
    List<AttachmentOwnerState> findAttachmentOwnerStates(@Param("ids") Collection<Long> ids);

    /** 첨부 정리 잡의 고아 판정 가드 — 이 DB가 발급한 적 있는 id의 상한. 빈 테이블이면 null. */
    @Query("SELECT MAX(p.id) FROM RequestPost p")
    Long findMaxId();
}
