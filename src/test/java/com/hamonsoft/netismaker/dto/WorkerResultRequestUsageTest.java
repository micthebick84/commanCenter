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

    @Test
    void isEmpty_모든_필드가_null이면_true() {
        var usage = new WorkerResultRequest.UsageReport(null, null, null, null, null);
        assertThat(usage.isEmpty()).isTrue();
    }

    @Test
    void isEmpty_스케일있는_0과_토큰_전부_0이면_true() {
        // BigDecimal("0.00")은 scale=2 지만 signum()==0 이어야 함 — compareTo 기반 분기 검증
        var usage = new WorkerResultRequest.UsageReport(
                new BigDecimal("0.00"), 0L, 0L, 0L, 0L);
        assertThat(usage.isEmpty()).isTrue();
    }

    @Test
    void isEmpty_costUsd_null이어도_토큰이_있으면_false() {
        var usage = new WorkerResultRequest.UsageReport(null, 1L, null, null, null);
        assertThat(usage.isEmpty()).isFalse();
    }

    @Test
    void isEmpty_토큰_전부_null이어도_costUsd가_0보다_크면_false() {
        var usage = new WorkerResultRequest.UsageReport(
                new BigDecimal("0.000001"), null, null, null, null);
        assertThat(usage.isEmpty()).isFalse();
    }

    @Test
    void isEmpty_토큰_하나만_0이고_나머지_null이면_true() {
        var usage = new WorkerResultRequest.UsageReport(null, 0L, null, null, null);
        assertThat(usage.isEmpty()).isTrue();
    }
}
