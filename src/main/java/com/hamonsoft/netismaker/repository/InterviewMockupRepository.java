package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.dto.InterviewMockupSummary;
import com.hamonsoft.netismaker.entity.InterviewMockup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 인터뷰 목업 (스펙 2026-11-03 §5). "버린" 행(discarded)은 목록·단건·상한·확정 검증에서 모두 뺀다. */
public interface InterviewMockupRepository extends JpaRepository<InterviewMockup, Long> {

    /**
     * 세션의 목업 목록 — 버린 것 제외, n 순. html 칸을 읽지 않는다: 이 프로젝트는 바이트코드 보강이 없어
     * {@code @Basic(LAZY)}가 효과가 없으므로, 엔티티 대신 JPQL 생성자 식으로 칸을 골라 읽는다(200KB × 40개를 목록마다 읽지 않게).
     */
    @Query("""
        SELECT new com.hamonsoft.netismaker.dto.InterviewMockupSummary(
            m.id, m.n, m.name, m.version, m.title, m.description, m.turnSeq, m.turnKind, m.createdAt)
        FROM InterviewMockup m
        WHERE m.sessionId = :sessionId AND m.discarded = false
        ORDER BY m.n
        """)
    List<InterviewMockupSummary> findSummaries(@Param("sessionId") Long sessionId);

    /** 단건 — 그 세션의 버리지 않은 목업만(다른 세션의 id는 비어 있음). */
    Optional<InterviewMockup> findByIdAndSessionIdAndDiscardedFalse(Long id, Long sessionId);

    /** 확정 때 넘길 목업 조회 — id 목록 중 버리지 않은 것을 n 순으로. 세션 소속은 호출자가 맞춘다. */
    List<InterviewMockup> findByIdInAndDiscardedFalseOrderByNAsc(Collection<Long> ids);

    /** 세션의 가장 큰 n — 버린 행 포함(버린 번호는 다시 쓰지 않는다), 없으면 0. */
    @Query("SELECT COALESCE(MAX(m.n), 0) FROM InterviewMockup m WHERE m.sessionId = :sessionId")
    int maxN(@Param("sessionId") Long sessionId);

    /** 그 이름의 가장 큰 버전 — 버린 행 제외(버린 버전은 다시 쓸 수 있다), 없으면 0. */
    @Query("""
        SELECT COALESCE(MAX(m.version), 0) FROM InterviewMockup m
        WHERE m.sessionId = :sessionId AND m.name = :name AND m.discarded = false
        """)
    int maxVersion(@Param("sessionId") Long sessionId, @Param("name") String name);

    /** 세션당 상한 판정용 — 버린 것 제외. */
    long countBySessionIdAndDiscardedFalse(Long sessionId);

    /**
     * 턴이 질문·계획으로 끝날 때 그 세션의 안 묶인(turn_seq NULL, 버리지 않은) 목업을 그 턴에 묶는다. 반환 = 묶은 행 수.
     * 이미 묶인 행·버린 행은 건드리지 않는다. 단일 UPDATE 문이라 호출자의 트랜잭션에 합류하고, 없어도 혼자 돈다.
     */
    @Transactional
    @Modifying
    @Query("""
        UPDATE InterviewMockup m SET m.turnSeq = :turnSeq, m.turnKind = :turnKind
        WHERE m.sessionId = :sessionId AND m.turnSeq IS NULL AND m.discarded = false
        """)
    int bindUnbound(@Param("sessionId") Long sessionId, @Param("turnSeq") int turnSeq, @Param("turnKind") String turnKind);

    /**
     * 끊긴 턴(재기동 재큐·턴 실패)에서 안 묶인 채 남은 목업을 버린다. 반환 = 버린 행 수. 묶인 행·이미 버린 행은 건드리지 않는다.
     */
    @Transactional
    @Modifying
    @Query("""
        UPDATE InterviewMockup m SET m.discarded = true
        WHERE m.sessionId = :sessionId AND m.turnSeq IS NULL AND m.discarded = false
        """)
    int discardUnbound(@Param("sessionId") Long sessionId);
}
