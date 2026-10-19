package com.hamonsoft.netismaker.repository;

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

    /** 관리자 주의 필요 건수: 접수이거나 관리자가 못 본 활동이 있는 요청. */
    @Query("""
            select count(p) from RequestPost p
            where p.status = com.hamonsoft.netismaker.entity.RequestStatus.RECEIVED
               or p.adminReadAt is null
               or p.lastRequesterActivityAt > p.adminReadAt
            """)
    long countAttention();

    long countByStatus(RequestStatus status);
}
