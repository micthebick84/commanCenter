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
}
