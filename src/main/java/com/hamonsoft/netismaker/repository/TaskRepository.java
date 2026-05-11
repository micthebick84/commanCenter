package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /**
     * 사용자별 미완료 작업 카운트. 동시 등록 제한(5건) 검증용.
     */
    @Query("""
        SELECT COUNT(t) FROM Task t
        WHERE t.requesterId = :requesterId
          AND t.deletedAt IS NULL
          AND t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.PENDING,
                           com.hamonsoft.netismaker.entity.TaskStatus.IN_PROGRESS,
                           com.hamonsoft.netismaker.entity.TaskStatus.COMPLETED,
                           com.hamonsoft.netismaker.entity.TaskStatus.FAILED)
    """)
    long countActiveByRequester(@Param("requesterId") Long requesterId);

    /**
     * 본인 작업 목록 (deleted_at IS NULL).
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.requesterId = :requesterId
          AND t.deletedAt IS NULL
          AND (:status IS NULL OR t.status = :status)
    """)
    Page<Task> findByRequester(@Param("requesterId") Long requesterId,
                               @Param("status") TaskStatus status,
                               Pageable pageable);

    /**
     * 전체 작업 목록 (ADMIN용).
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.deletedAt IS NULL
          AND (:status IS NULL OR t.status = :status)
    """)
    Page<Task> findAllActive(@Param("status") TaskStatus status, Pageable pageable);

    /**
     * 단건 조회 (soft-deleted 제외).
     */
    @Query("SELECT t FROM Task t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<Task> findActiveById(@Param("id") Long id);

    /**
     * 워커가 대기 작업 1건을 atomic하게 claim.
     * SELECT FOR UPDATE SKIP LOCKED. 동시 워커가 있어도 1개만 잡음.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT t FROM Task t
        WHERE t.status = com.hamonsoft.netismaker.entity.TaskStatus.PENDING
          AND t.deletedAt IS NULL
        ORDER BY t.createdAt ASC
    """)
    List<Task> findPendingForUpdateSkipLocked(Pageable pageable);

    /**
     * Stale 회수 잡: 분석중 상태 + 5분 이상 업데이트 없음.
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.status = com.hamonsoft.netismaker.entity.TaskStatus.IN_PROGRESS
          AND t.claimedAt < :threshold
          AND t.deletedAt IS NULL
    """)
    List<Task> findStaleInProgress(@Param("threshold") OffsetDateTime threshold);
}
