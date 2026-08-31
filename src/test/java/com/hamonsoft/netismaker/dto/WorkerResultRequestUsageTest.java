package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.TaskStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerResultRequestUsageTest {

    @Test
    void 팩토리는_usage를_보존한다() {
        var usage = new WorkerResultRequest.UsageReport(
                new BigDecimal("0.42"), 100L, 20L, 5L, 900L);
        WorkerResultRequest req = WorkerResultRequest.designReview(
                "w1", "# D", "[]", "p", "u", "log", 5L, usage);
        assertThat(req.usage()).isEqualTo(usage);
        assertThat(req.status()).isEqualTo(TaskStatus.DESIGN_REVIEW);
    }

    @Test
    void 배포_팩토리의_usage는_null이다() {
        WorkerResultRequest req = WorkerResultRequest.deployed(
                "w1", "http://u", "cid", 80, "img", 5L, "log");
        assertThat(req.usage()).isNull();
    }
}
