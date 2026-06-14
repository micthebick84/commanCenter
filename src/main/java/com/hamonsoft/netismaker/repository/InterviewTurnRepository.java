package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.InterviewTurn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InterviewTurnRepository extends JpaRepository<InterviewTurn, Long> {

    /** 세션의 전체 턴을 순서대로. claim 컨텍스트 + 상세 조회용. */
    List<InterviewTurn> findBySessionIdOrderBySeqAsc(Long sessionId);

    /** 다음 seq 계산용. 턴이 없으면 null. */
    @Query("SELECT MAX(t.seq) FROM InterviewTurn t WHERE t.sessionId = :sessionId")
    Integer findMaxSeq(@Param("sessionId") Long sessionId);
}
