package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구현 프롬프트의 {plan_section} — 인터뷰 확정 플랜 본문을 구현 AI에 전달한다.
 * 플랜에는 writing-plans가 넣은 "git commit" 단계가 들어 있는데, 워커는 변경이 없으면
 * "nothing to commit"으로 구현실패 처리하므로 커밋 단계를 실행하지 말라고 함께 지시해야 한다.
 */
class WorkerMainLoopPlanSectionTest {

    private static final String PLAN = "# README 구현 계획\n\n### 작업 1: 섹션 삽입\n\n- [ ] 단계 4: 커밋\n  git commit -m \"docs\"";

    private static WorkerTaskResponse implementationTask(String planMarkdown) {
        Task t = Task.create("owner/repo", "main", "README에 사용 방법 섹션 추가", "요구사항 본문", "user1", 3,
                List.of(), "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 3L);
        return WorkerTaskResponse.forImplementation(t, null, null, planMarkdown);
    }

    @SuppressWarnings("unchecked")
    private static String workerYmlTemplate() {
        try (InputStream in = WorkerMainLoopPlanSectionTest.class.getResourceAsStream("/application-worker.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> worker = (Map<String, Object>) ((Map<String, Object>) root.get("netis-maker")).get("worker");
            return (String) worker.get("implementation-prompt-template");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void plan_section_carries_the_plan_and_tells_the_agent_not_to_commit() {
        String section = WorkerMainLoop.renderPlanSection(implementationTask(PLAN));

        assertThat(section).contains("### 작업 1: 섹션 삽입");
        assertThat(section).contains("확정된 구현 계획");
        assertThat(section).contains("커밋");          // 커밋 단계 실행 금지 안내
        assertThat(section).contains("워커");
    }

    @Test
    void no_plan_renders_nothing_so_legacy_tasks_are_unchanged() {
        assertThat(WorkerMainLoop.renderPlanSection(implementationTask(null))).isEmpty();
        assertThat(WorkerMainLoop.renderPlanSection(implementationTask("  "))).isEmpty();
    }

    @Test
    void the_live_worker_yml_template_includes_the_plan() {
        String prompt = WorkerMainLoop.fillImplementationPrompt(workerYmlTemplate(), implementationTask(PLAN),
                "35c9110", "netismaker/task-3-readme");

        assertThat(prompt).contains("### 작업 1: 섹션 삽입");
        assertThat(prompt).doesNotContain("{plan_section}");
        assertThat(prompt).contains("요구사항 본문");
    }

    @Test
    void the_default_template_includes_the_plan_too() {
        String prompt = WorkerMainLoop.fillImplementationPrompt(WorkerMainLoop.defaultImplementationPrompt(),
                implementationTask(PLAN), "35c9110", "netismaker/task-3-readme");

        assertThat(prompt).contains("### 작업 1: 섹션 삽입");
        assertThat(prompt).doesNotContain("{plan_section}");
    }

    @Test
    void without_a_plan_the_rendered_prompt_has_no_plan_heading() {
        String prompt = WorkerMainLoop.fillImplementationPrompt(workerYmlTemplate(), implementationTask(null),
                "35c9110", "netismaker/task-3-readme");

        assertThat(prompt).doesNotContain("확정된 구현 계획");
        assertThat(prompt).doesNotContain("{plan_section}");
    }

    /** 직접 방식 문구는 한 글자도 바뀌지 않는다 — 리뷰 방식과 공유하는 틀을 건드리는 변경이 여기서 걸린다. */
    @Test
    void the_direct_plan_section_text_is_pinned_character_for_character() {
        String expected = """

                ## 확정된 구현 계획 (인터뷰에서 승인됨 — 이 계획을 기준으로 구현)
                아래는 요청자와의 인터뷰로 확정된 구현 계획입니다. 작업 순서·범위·파일 위치는 이 계획을 따르세요.
                - 계획 안의 git commit / push 단계는 실행하지 마세요 — 커밋은 워커가 마지막에 한 번에 처리합니다.
                - 계획의 확인·검증 단계(테스트, grep 등)는 가능하면 수행하고 결과를 출력에 남기세요.
                - 계획이 가리키는 서브에이전트/실행 스킬(subagent-driven-development 등) 없이 이 세션에서 직접 구현하세요.

                """ + PLAN + "\n";

        assertThat(WorkerMainLoop.renderPlanSection(implementationTask(PLAN))).isEqualTo(expected);
    }

    @Test
    void both_templates_take_the_commit_rule_and_keep_the_direct_wording() {
        for (String tpl : List.of(workerYmlTemplate(), WorkerMainLoop.defaultImplementationPrompt())) {
            assertThat(tpl).contains("{commit_rule}");
            String prompt = WorkerMainLoop.fillImplementationPrompt(tpl, implementationTask(PLAN),
                    "35c9110", "netismaker/task-3-readme");
            assertThat(prompt).doesNotContain("{commit_rule}")
                    .contains("의미 있는 변경을 누적하세요 (커밋은 워커가 마지막에 한 번에 처리)");
        }
    }

    @Test
    void the_six_argument_fill_uses_the_given_plan_section_and_commit_rule() {
        String prompt = WorkerMainLoop.fillImplementationPrompt(workerYmlTemplate(), implementationTask(PLAN),
                "35c9110", "netismaker/task-3-readme", "\n## SDD 절\n", ImplementationPrompts.SDD_COMMIT_RULE);

        assertThat(prompt).contains("## SDD 절")
                .contains(ImplementationPrompts.SDD_COMMIT_RULE)
                .doesNotContain("### 작업 1: 섹션 삽입")
                .endsWith(WorkerMainLoop.DEPLOY_ENV_INSTRUCTION);
    }
}
