package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.entity.FixOutcome;
import com.hamonsoft.netismaker.entity.FixRequestStatus;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskFixRequest;
import com.hamonsoft.netismaker.entity.TaskStatus;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 수정 요청 회차 현황(fix)·기록 뷰·요청 바디 검증 (스펙 2026-11-03 §6·§8). */
class TaskResponseFixTest {

    private static final TaskResponse.FixView FIX = new TaskResponse.FixView(2, "DIRECT", 2, 3);

    private Task task() {
        Task t = Task.create("owner/repo", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 1L);
        t.setStatus(TaskStatus.DEPLOYED);
        t.setEnvVars(new ArrayList<>(List.of(new EnvVar("DB_PASSWORD", "pw", true))));
        return t;
    }

    @Test
    void 기본_응답과_목록용_재조립은_fix가_null() {
        TaskResponse r = TaskResponse.of(task(), null);
        assertThat(r.fix()).isNull();
        assertThat(TaskResponse.ofWithUsage(task(), null, null, null, List.of(), List.of()).fix()).isNull();
        assertThat(TaskResponse.withTotalCost(r, BigDecimal.ONE).fix()).isNull();
    }

    @Test
    void withFix는_fix만_바꾸고_나머지는_그대로() {
        TaskResponse r = TaskResponse.of(task(), null);
        TaskResponse withFix = r.withFix(FIX);
        assertThat(withFix.fix()).isEqualTo(FIX);
        assertThat(withFix).usingRecursiveComparison().ignoringFields("fix").isEqualTo(r);
    }

    @Test
    void 다른_사본_만들기가_fix를_보존한다() {
        TaskResponse r = TaskResponse.of(task(), null).withFix(FIX);
        assertThat(r.redactSecretValues().fix()).isEqualTo(FIX);
        assertThat(r.withRefRepos(List.of()).fix()).isEqualTo(FIX);
        assertThat(TaskResponse.withTotalCost(r, BigDecimal.TEN).fix()).isEqualTo(FIX);
    }

    @Test
    void 수정_요청_기록_뷰는_enum을_이름으로_준다() {
        TaskFixRequest req = TaskFixRequest.pending(1L, "본문", TaskStatus.DEPLOY_LOST, "user1");
        ReflectionTestUtils.setField(req, "id", 9L);
        FixRequestView pending = FixRequestView.of(req);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.implementationMode()).isNull();
        assertThat(pending.round()).isNull();
        assertThat(pending.outcome()).isNull();
        assertThat(pending.attempt()).isEqualTo(1);

        req.setStatus(FixRequestStatus.APPROVED);
        req.setRound(1);
        req.setImplementationMode(ImplementationMode.REVIEW);
        req.setOutcome(FixOutcome.DEPLOY_FAILED);
        FixRequestView done = FixRequestView.of(req);
        assertThat(done.id()).isEqualTo(9L);
        assertThat(done.status()).isEqualTo("APPROVED");
        assertThat(done.implementationMode()).isEqualTo("REVIEW");
        assertThat(done.round()).isEqualTo(1);
        assertThat(done.outcome()).isEqualTo("DEPLOY_FAILED");
        assertThat(done.body()).isEqualTo("본문");
        assertThat(done.requestedBy()).isEqualTo("user1");
    }

    @Test
    void 요청_바디_검증() {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(v.validate(new FixRequestCreateRequest("본문", null))).isEmpty();
        assertThat(v.validate(new FixRequestCreateRequest("가".repeat(20_000), "DIRECT"))).isEmpty();
        assertThat(v.validate(new FixRequestCreateRequest(" ", null))).hasSize(1);
        assertThat(v.validate(new FixRequestCreateRequest(null, null))).hasSize(1);
        assertThat(v.validate(new FixRequestCreateRequest("가".repeat(20_001), null))).hasSize(1);
        assertThat(v.validate(new FixRequestRejectRequest("사유"))).isEmpty();
        assertThat(v.validate(new FixRequestRejectRequest(""))).hasSize(1);
        assertThat(v.validate(new FixRequestRejectRequest("가".repeat(2_001)))).hasSize(1);
    }
}
