package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.QueueStats;
import com.hamonsoft.netismaker.repository.QueueStatsRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 큐 통계 (모든 로그인 사용자). DESIGN §12.
 *   GET /api/queue/stats
 */
@RestController
@RequestMapping("/api/queue")
@Profile("api")
public class QueueStatsController {

    private final QueueStatsRepository repo;

    public QueueStatsController(QueueStatsRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/stats")
    public QueueStats stats() {
        return repo.fetch();
    }
}
