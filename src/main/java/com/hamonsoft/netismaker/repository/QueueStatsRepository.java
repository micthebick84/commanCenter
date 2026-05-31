package com.hamonsoft.netismaker.repository;

import com.hamonsoft.netismaker.dto.QueueStats;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * com.task_queue_stats 뷰를 native 쿼리로 조회. 단일 행 반환.
 */
@Repository
@Profile("api")
public class QueueStatsRepository {

    private final EntityManager em;

    public QueueStatsRepository(EntityManager em) {
        this.em = em;
    }

    public QueueStats fetch() {
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT pending, in_progress, awaiting_approval,
                       approved, implementing, pr_created, implementation_failed, failed,
                       deploy_pending, deploying, deployed, deploy_failed,
                       undeploy_pending, undeploying,
                       avg_duration_ms
                FROM com.task_queue_stats
                """).getSingleResult();
        return new QueueStats(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(),
                ((Number) row[3]).longValue(),
                ((Number) row[4]).longValue(),
                ((Number) row[5]).longValue(),
                ((Number) row[6]).longValue(),
                ((Number) row[7]).longValue(),
                ((Number) row[8]).longValue(),
                ((Number) row[9]).longValue(),
                ((Number) row[10]).longValue(),
                ((Number) row[11]).longValue(),
                ((Number) row[12]).longValue(),
                ((Number) row[13]).longValue(),
                row[14] == null ? null : ((Number) row[14]).doubleValue()
        );
    }
}
