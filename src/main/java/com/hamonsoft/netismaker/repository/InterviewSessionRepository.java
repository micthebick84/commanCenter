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
}
