package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchRequest;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 워커 묶음 수신: 정리 → upsert → 수집기 상태 갱신(빈 묶음 포함). */
@Service
@Profile("api")
public class ClaudeCodeUsageIngestService {

    private final ClaudeCodeUsageEventStore store;

    public ClaudeCodeUsageIngestService(ClaudeCodeUsageEventStore store) {
        this.store = store;
    }

    @Transactional
    public ClaudeCodeUsageBatchResponse ingest(ClaudeCodeUsageBatchRequest req) {
        int received = req.events() == null ? 0 : req.events().size();
        var clean = ClaudeCodeUsageSanitizer.sanitize(req.events());
        var counts = store.upsert(req.machine(), clean.events());
        store.touchCollector(req.machine(), clean.lastEventAt(),
                req.unreadableLines() == null ? 0 : req.unreadableLines());
        return new ClaudeCodeUsageBatchResponse(received, counts.inserted(), counts.updated(), clean.skipped());
    }
}
