package com.hamonsoft.netismaker.usage;

import com.hamonsoft.netismaker.dto.ClaudeCodeUsageBatchRequest;
import com.hamonsoft.netismaker.dto.ClaudeCodeUsageEvent;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 저장소는 가짜 — PostgreSQL이 NUL(0x00)을 거부하므로 저장소에 넘기는 machine에 NUL이 없어야 한다. */
class ClaudeCodeUsageIngestServiceTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-01T00:00:00+09:00");

    @Test
    void machine의_NUL을_지우고_저장한다() {
        var store = mock(ClaudeCodeUsageEventStore.class);
        when(store.upsert(anyString(), anyList())).thenReturn(new ClaudeCodeUsageEventStore.UpsertCounts(1, 0));
        var ev = new ClaudeCodeUsageEvent("k", T0, "SYMPHONY", "m", null, null, null, null, false, 1, 1, 0, 0, 0);

        var res = new ClaudeCodeUsageIngestService(store)
                .ingest(new ClaudeCodeUsageBatchRequest("pc\u0000-1", 0, List.of(ev)));

        verify(store).upsert(eq("pc-1"), anyList());
        verify(store).touchCollector("pc-1", T0, 0);
        assertThat(res.inserted()).isEqualTo(1);
    }
}
