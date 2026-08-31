package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.entity.TaskStageUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface TaskStageUsageRepository extends JpaRepository<TaskStageUsage, TaskStageUsage.Key> {

    /** 누적 upsert — 동시 보고에도 PK 충돌 시 원자적 가산 (스펙 §3). */
    @Modifying
    @Query(value = """
            INSERT INTO com.task_stage_usage
                (task_id, stage, cost_usd, input_tokens, output_tokens,
                 cache_creation_tokens, cache_read_tokens, updated_at)
            VALUES (:taskId, :stage, :costUsd, :inputTokens, :outputTokens,
                    :cacheCreationTokens, :cacheReadTokens, now())
            ON CONFLICT (task_id, stage) DO UPDATE SET
                cost_usd              = task_stage_usage.cost_usd + EXCLUDED.cost_usd,
                input_tokens          = task_stage_usage.input_tokens + EXCLUDED.input_tokens,
                output_tokens         = task_stage_usage.output_tokens + EXCLUDED.output_tokens,
                cache_creation_tokens = task_stage_usage.cache_creation_tokens + EXCLUDED.cache_creation_tokens,
                cache_read_tokens     = task_stage_usage.cache_read_tokens + EXCLUDED.cache_read_tokens,
                updated_at            = now()
            """, nativeQuery = true)
    void accumulate(@Param("taskId") Long taskId, @Param("stage") String stage,
                    @Param("costUsd") BigDecimal costUsd,
                    @Param("inputTokens") long inputTokens,
                    @Param("outputTokens") long outputTokens,
                    @Param("cacheCreationTokens") long cacheCreationTokens,
                    @Param("cacheReadTokens") long cacheReadTokens);

    List<TaskStageUsage> findByTaskIdOrderByStageAsc(Long taskId);

    interface CostTotal {
        Long getTaskId();
        BigDecimal getTotalCostUsd();
    }

    @Query(value = """
            SELECT task_id AS taskId, SUM(cost_usd) AS totalCostUsd
            FROM com.task_stage_usage WHERE task_id IN (:taskIds) GROUP BY task_id
            """, nativeQuery = true)
    List<CostTotal> sumCostByTaskIds(@Param("taskIds") List<Long> taskIds);
}
