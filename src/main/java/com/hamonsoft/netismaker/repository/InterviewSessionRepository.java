package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.InterviewSession;
import com.hamonsoft.netismaker.entity.InterviewStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {

    /** 단건 조회 (인터뷰는 soft-delete 없음 — id로 직접). */
    @Query("SELECT s FROM InterviewSession s WHERE s.id = :id")
    Optional<InterviewSession> findActiveById(@Param("id") Long id);

    /**
     * 단건 조회 + SELECT ... FOR UPDATE (SKIP LOCKED 없음).
     *
     * 세션 상태 전이(recordQuestion/fail/expire 등)처럼 "읽은 상태를 근거로 전이"하는
     * 흐름에서 쓴다. claim(SKIP LOCKED)과 스윕(expire/fail)이 같은 행을 두고 경합하므로,
     * 나중에 온 트랜잭션은 앞선 커밋을 기다렸다가 갱신된 상태를 재판정해 상태 가드에서
     * 자연히 409로 거절된다. SKIP LOCKED를 넣으면 두 번째 호출이 조용히 건너뛰어
     * "기다렸다가 최신 상태로 재판정"이 불가능해지므로 넣지 않는다
     * (TaskRepository.findActiveByIdForUpdate와 동일한 wait-then-re-judge 의미론).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InterviewSession s WHERE s.id = :id")
    Optional<InterviewSession> findByIdForUpdate(@Param("id") Long id);

    /**
     * 워커가 다음에 처리할 인터뷰 1건을 atomic claim.
     * SELECT FOR UPDATE SKIP LOCKED. 동시 워커가 있어도 1개만 잡음.
     *
     * 순서 = last_activity_at ASC: 가장 오래 대기한 세션 우선. 답변 직후 재큐된 세션은
     * last_activity_at이 갱신되어 자연히 뒤로 가므로 신규/오래된 세션이 굶지 않는다.
     * claim 후보 = QUEUED (신규 또는 답변 후 재큐). claude_session_id null이면 신규,
     * 있으면 resume. TaskRepository.findClaimableForUpdateSkipLocked와 동일 패턴.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
        SELECT s FROM InterviewSession s
        WHERE s.status = com.hamonsoft.netismaker.entity.InterviewStatus.QUEUED
        ORDER BY s.lastActivityAt ASC
    """)
    List<InterviewSession> findClaimableForUpdateSkipLocked(Pageable pageable);

    /**
     * Stale 회수 잡: 워커가 claim해 처리중인(in-flight = RUNNING) 모든 세션.
     * 회수 여부는 heartbeat + claimed_at으로 판정 (TaskRepository.findInFlightClaimed 미러).
     */
    @Query("""
        SELECT s FROM InterviewSession s
        WHERE s.status = com.hamonsoft.netismaker.entity.InterviewStatus.RUNNING
          AND s.workerId IS NOT NULL
    """)
    List<InterviewSession> findInFlightClaimed();

    /** 본인/관리자 목록 조회 (status 필터 옵션). */
    @Query("""
        SELECT s FROM InterviewSession s
        WHERE s.requesterId = :requesterId
          AND (:status IS NULL OR s.status = :status)
        ORDER BY s.createdAt DESC
    """)
    List<InterviewSession> findByRequester(@Param("requesterId") String requesterId,
                                           @Param("status") InterviewStatus status);

    /** RUNNING이면서 claimed_at이 cutoff 이전(=stale) — 회수 후보. */
    @Query("""
        SELECT s FROM InterviewSession s
        WHERE s.status = com.hamonsoft.netismaker.entity.InterviewStatus.RUNNING
          AND s.claimedAt IS NOT NULL AND s.claimedAt < :cutoff
    """)
    List<InterviewSession> findStaleRunning(@Param("cutoff") OffsetDateTime cutoff);

    /** AWAITING_INPUT이면서 last_activity_at이 cutoff 이전(=idle TTL 초과) — 만료 후보. */
    @Query("""
        SELECT s FROM InterviewSession s
        WHERE s.status = com.hamonsoft.netismaker.entity.InterviewStatus.AWAITING_INPUT
          AND s.lastActivityAt < :cutoff
    """)
    List<InterviewSession> findIdleAwaitingInput(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * QUEUED 상태로 cutoff 이전부터 대기 중인 세션 — 인터뷰 서비스 미가동 감지용.
     * lastActivityAt은 큐 진입 시점(createForTask/submitAnswer의 touch())이므로
     * '대기 시작' 시계로 정확하다. V11의 부분 인덱스 idx_interview_queued가 커버.
     */
    @Query("""
        SELECT s FROM InterviewSession s
        WHERE s.status = com.hamonsoft.netismaker.entity.InterviewStatus.QUEUED
          AND s.lastActivityAt < :cutoff
    """)
    List<InterviewSession> findStaleQueued(@Param("cutoff") OffsetDateTime cutoff);

    /** task의 최신 세션 1건 (재승인으로 세션이 여러 개일 수 있다). */
    Optional<InterviewSession> findTopByTaskIdOrderByCreatedAtDesc(Long taskId);

    /** task에 붙은 비종료 세션들 — 삭제 시 정리 대상. */
    @Query("""
        SELECT s FROM InterviewSession s
        WHERE s.taskId = :taskId
          AND s.status IN (com.hamonsoft.netismaker.entity.InterviewStatus.QUEUED,
                           com.hamonsoft.netismaker.entity.InterviewStatus.RUNNING,
                           com.hamonsoft.netismaker.entity.InterviewStatus.AWAITING_INPUT,
                           com.hamonsoft.netismaker.entity.InterviewStatus.PLAN_READY)
    """)
    List<InterviewSession> findOpenByTaskId(@Param("taskId") Long taskId);
}
