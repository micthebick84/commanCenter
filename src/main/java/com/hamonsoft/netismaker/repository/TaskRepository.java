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
    long countActiveByRequester(@Param("requesterId") String requesterId);

    /**
     * 본인 작업 목록 (deleted_at IS NULL).
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.requesterId = :requesterId
          AND t.deletedAt IS NULL
          AND (:status IS NULL OR t.status = :status)
    """)
    Page<Task> findByRequester(@Param("requesterId") String requesterId,
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
     * 워커가 다음에 처리할 작업 1건을 atomic claim.
     * SELECT FOR UPDATE SKIP LOCKED. 동시 워커가 있어도 1개만 잡음.
     *
     * 분석(PENDING) + 구현(APPROVED) 둘 다 후보. FIFO(createdAt 오래된 순).
     * 구현이 분석보다 훨씬 오래 걸리므로 같은 워커가 잡으면 다른 분석이 대기.
     * 워커 1대 직렬 제약(DESIGN P6) 하에서는 의도된 동작. V2 멀티워커 시 큐 분리.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT t FROM Task t
        WHERE t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.PENDING,
                           com.hamonsoft.netismaker.entity.TaskStatus.APPROVED)
          AND t.deletedAt IS NULL
        ORDER BY t.createdAt ASC
    """)
    List<Task> findClaimableForUpdateSkipLocked(Pageable pageable);

    /** 호환 alias. 기존 호출처 단계적 마이그레이션 위함. */
    default List<Task> findPendingForUpdateSkipLocked(Pageable pageable) {
        return findClaimableForUpdateSkipLocked(pageable);
    }

    /**
     * Stale 회수 잡: 분석중/구현중 상태 + threshold 이상 업데이트 없음.
     * 회수 시 구현중(IMPLEMENTING)은 IMPLEMENTATION_FAILED로 (재시도하면 partial 변경 위험),
     * 분석중(IN_PROGRESS)은 기존 정책대로 PENDING으로 (recovery job 참고).
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.IN_PROGRESS,
                           com.hamonsoft.netismaker.entity.TaskStatus.IMPLEMENTING)
          AND t.claimedAt < :threshold
          AND t.deletedAt IS NULL
    """)
    List<Task> findStaleInProgress(@Param("threshold") OffsetDateTime threshold);
}
