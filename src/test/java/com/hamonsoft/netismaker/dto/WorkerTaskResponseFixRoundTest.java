package com.hamonsoft.netismaker.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamonsoft.netismaker.entity.ImplementationMode;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 구현 claim의 수정 회차(FixRoundSpec) — 회차 없는 구현은 예전 모양 그대로(fix=null). */
class WorkerTaskResponseFixRoundTest {

    private static Task task(ImplementationMode mode) {
        Task t = Task.create("a/b", "main", "T", "d", "user1", 3, List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        t.setImplementationMode(mode);
        t.setImplementationAttempt(2);
        t.setImplementationTimeoutMinutes(45);
        return t;
    }

    private static WorkerTaskResponse.FixRoundSpec fix() {
        return new WorkerTaskResponse.FixRoundSpec(502L, 2, 1, "대상장비 탭 갱신 안 됨",
                List.of("장기분석 로딩"), "netismaker/task-42-x", "0123456789abcdef0123456789abcdef01234567");
    }

    @Test
    void 세_인자_ImplementationSpec은_fix가_null() {
        var spec = new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45);

        assertThat(spec.fix()).isNull();
        assertThat(spec).isEqualTo(new WorkerTaskResponse.ImplementationSpec("DIRECT", 1, 45, null));
    }

    @Test
    void 네_인자_forImplementation은_fix_없이_예전_그대로() {
        WorkerTaskResponse r = WorkerTaskResponse.forImplementation(task(ImplementationMode.REVIEW), null, null, "# 플랜");

        assertThat(r.implementation()).isEqualTo(new WorkerTaskResponse.ImplementationSpec("REVIEW", 2, 45));
        assertThat(r.implementation().fix()).isNull();
        assertThat(r.planMarkdown()).isEqualTo("# 플랜");
    }

    @Test
    void 다섯_인자_forImplementation은_fix를_싣고_방식은_작업_것() {
        WorkerTaskResponse r = WorkerTaskResponse.forImplementation(task(ImplementationMode.DIRECT), null, null, "# 플랜", fix());

        assertThat(r.implementation().mode()).isEqualTo("DIRECT");
        assertThat(r.implementation().attempt()).isEqualTo(2);
        assertThat(r.implementation().timeoutMinutes()).isEqualTo(45);
        assertThat(r.implementation().fix()).isEqualTo(fix());
    }

    @Test
    void 방식을_따로_주면_회차의_방식으로_싣는다() {
        WorkerTaskResponse r = WorkerTaskResponse.forImplementation(task(ImplementationMode.DIRECT), null, null, "# 플랜",
                fix(), ImplementationMode.REVIEW);

        assertThat(r.implementation().mode()).isEqualTo("REVIEW");
        assertThat(r.implementation().fix()).isEqualTo(fix());
    }

    @Test
    void JSON에는_implementation_fix로_나간다() throws Exception {
        ObjectMapper om = new ObjectMapper();

        JsonNode withFix = om.valueToTree(WorkerTaskResponse.forImplementation(task(ImplementationMode.DIRECT), null, null, null, fix()));
        JsonNode fixNode = withFix.path("implementation").path("fix");
        assertThat(fixNode.path("requestId").asLong()).isEqualTo(502L);
        assertThat(fixNode.path("round").asInt()).isEqualTo(2);
        assertThat(fixNode.path("attempt").asInt()).isEqualTo(1);
        assertThat(fixNode.path("body").asText()).isEqualTo("대상장비 탭 갱신 안 됨");
        assertThat(fixNode.path("previousBodies").get(0).asText()).isEqualTo("장기분석 로딩");
        assertThat(fixNode.path("headBranch").asText()).isEqualTo("netismaker/task-42-x");
        assertThat(fixNode.path("headSha").asText()).hasSize(40);

        JsonNode without = om.valueToTree(WorkerTaskResponse.forImplementation(task(ImplementationMode.DIRECT), null, null, null));
        assertThat(without.path("implementation").path("fix").isNull()).isTrue();

        // 워커(구버전 포함)가 읽는 모양 — 왕복해도 같다
        var back = om.treeToValue(withFix, WorkerTaskResponse.class);
        assertThat(back.implementation().fix()).isEqualTo(fix());
    }
}
