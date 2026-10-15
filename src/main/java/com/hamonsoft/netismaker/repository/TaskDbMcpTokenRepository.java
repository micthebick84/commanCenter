package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskDbMcpToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface TaskDbMcpTokenRepository extends JpaRepository<TaskDbMcpToken, Long> {

    Optional<TaskDbMcpToken> findByTokenHash(String tokenHash);

    Optional<TaskDbMcpToken> findFirstByTaskIdOrderByIdDesc(Long taskId);

    /** 작업의 토큰 전부 삭제 — 재claim에서 쓸 연결이 없어 새 토큰을 안 줄 때 이전 토큰이 되살아나지 않게. */
    @Modifying
    @Query("DELETE FROM TaskDbMcpToken t WHERE t.taskId = :taskId")
    int deleteByTaskId(@Param("taskId") Long taskId);

    /** 만료됐거나 같은 작업의 더 새 토큰에 밀린 행 삭제. */
    @Modifying
    @Query(value = """
        DELETE FROM com.task_db_mcp_token t
        WHERE t.expires_at < :now
           OR t.id < (SELECT MAX(x.id) FROM com.task_db_mcp_token x WHERE x.task_id = t.task_id)
        """, nativeQuery = true)
    int deleteExpiredOrSuperseded(@Param("now") OffsetDateTime now);
}
