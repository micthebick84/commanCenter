package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskDbWriteLog;
import com.hamonsoft.netismaker.entity.WriteClassification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/** 쓰기 SQL 기록 (스펙 2026-11-03 §6.5). */
public interface TaskDbWriteLogRepository extends JpaRepository<TaskDbWriteLog, Long> {

    /** 작업의 기록을 시간순(같은 시각이면 id순)으로. 화면·스크립트 파일·PR 본문이 같은 순서를 쓴다. */
    List<TaskDbWriteLog> findByTaskIdOrderByCreatedAtAscIdAsc(Long taskId);

    boolean existsByTaskIdAndClassificationIn(Long taskId, Collection<WriteClassification> classifications);

    /**
     * 기록 한 행의 분류를 `from`일 때만 `to`로 바꾼다(조건부 UPDATE 한 문장) — 반환 = 바뀐 행 수(0 또는 1).
     * 승인 대기 "실행"·"실행 안 함"을 두 번 누르거나 두 관리자가 동시에 눌러도 한 번만 1을 돌려준다.
     * `taskId`를 같이 걸어 다른 작업의 기록 id로 처리하지 못하게 한다(IDOR). 0이면 호출자가 409로 막는다.
     * executed_at·executed_by는 이 전이를 한 시각·사람이다(DISMISSED도 같다).
     *
     * 호출자(관리자 실행 경로)는 트랜잭션 밖이라 여기서 트랜잭션을 연다 — 없으면 @Modifying 쿼리가
     * TransactionRequiredException으로 500이 난다.
     *
     * 시각은 앱 쪽 now를 `transitionAt`에 파라미터로 넘긴다. JPQL의 `CURRENT_TIMESTAMP`는 java.sql.Timestamp라
     * OffsetDateTime 칸에 대입하면 Hibernate가 SemanticException으로 거부한다(H2 하니스로 확인).
     */
    @Transactional
    default int transition(Long id, Long taskId, WriteClassification from, WriteClassification to, String executedBy) {
        return transitionAt(id, taskId, from, to, executedBy, OffsetDateTime.now());
    }

    @Transactional
    @Modifying
    @Query("""
            UPDATE TaskDbWriteLog l
               SET l.classification = :toClass, l.executedBy = :executedBy, l.executedAt = :now
             WHERE l.id = :id AND l.taskId = :taskId AND l.classification = :fromClass
            """)
    int transitionAt(@Param("id") Long id, @Param("taskId") Long taskId,
                     @Param("fromClass") WriteClassification from, @Param("toClass") WriteClassification to,
                     @Param("executedBy") String executedBy, @Param("now") OffsetDateTime now);
}
