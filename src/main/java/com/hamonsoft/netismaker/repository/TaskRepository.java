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
          AND t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.AWAITING_APPROVAL,
                           com.hamonsoft.netismaker.entity.TaskStatus.INTERVIEWING,
                           com.hamonsoft.netismaker.entity.TaskStatus.INTERVIEW_INPUT,
                           com.hamonsoft.netismaker.entity.TaskStatus.INTERVIEW_REVIEW,
                           com.hamonsoft.netismaker.entity.TaskStatus.PENDING,
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
                           com.hamonsoft.netismaker.entity.TaskStatus.APPROVED,
                           com.hamonsoft.netismaker.entity.TaskStatus.DESIGN_PENDING,
                           com.hamonsoft.netismaker.entity.TaskStatus.DEPLOY_PENDING,
                           com.hamonsoft.netismaker.entity.TaskStatus.UNDEPLOY_PENDING)
          AND t.deletedAt IS NULL
        ORDER BY t.createdAt ASC
    """)
    List<Task> findClaimableForUpdateSkipLocked(Pageable pageable);

    /** 호환 alias. 기존 호출처 단계적 마이그레이션 위함. */
    default List<Task> findPendingForUpdateSkipLocked(Pageable pageable) {
        return findClaimableForUpdateSkipLocked(pageable);
    }

    /**
     * Stale 회수 잡: 워커가 claim해 처리중인(in-flight) 모든 작업.
     * 회수 여부(워커 사망/행업)는 StaleTaskRecoveryJob이 heartbeat + claimed_at로 판정한다.
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.IN_PROGRESS,
                           com.hamonsoft.netismaker.entity.TaskStatus.IMPLEMENTING,
                           com.hamonsoft.netismaker.entity.TaskStatus.DESIGNING,
                           com.hamonsoft.netismaker.entity.TaskStatus.DEPLOYING,
                           com.hamonsoft.netismaker.entity.TaskStatus.UNDEPLOYING)
          AND t.workerId IS NOT NULL
          AND t.deletedAt IS NULL
    """)
    List<Task> findInFlightClaimed();

    /**
     * 배포 계열 활성 task: reconcile 관측 + GC 보호 겸용.
     * DeployReconcileJob은 이 중 DEPLOYED/DEPLOY_LOST만 대사하고 나머지는 무시한다.
     * in-flight 상태(배포대기~중지중)를 포함하는 이유: GC 보호 목록이 DEPLOYED만 담으면
     * 재배포 클릭 직후(DEPLOY_PENDING) 보호가 풀려 GC가 새 컨테이너를 죽이는 race가 생긴다.
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.status IN (com.hamonsoft.netismaker.entity.TaskStatus.DEPLOYED,
                           com.hamonsoft.netismaker.entity.TaskStatus.DEPLOY_LOST,
                           com.hamonsoft.netismaker.entity.TaskStatus.DEPLOY_PENDING,
                           com.hamonsoft.netismaker.entity.TaskStatus.DEPLOYING,
                           com.hamonsoft.netismaker.entity.TaskStatus.UNDEPLOY_PENDING,
                           com.hamonsoft.netismaker.entity.TaskStatus.UNDEPLOYING)
          AND t.deletedAt IS NULL
    """)
    List<Task> findDeployReconcilable();
}
