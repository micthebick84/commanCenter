package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 디자인 단계에서 Claude Design(DesignSync 업로드·디자인 시스템 pull)을 걷어낸 프롬프트·PR 본문
 * (스펙 2026-10-31-design-phase-without-claude-design).
 */
class WorkerMainLoopDesignPromptTest {

    /** application-worker.yml을 실제로 읽어 바인딩한 설정 — 운영 템플릿 그대로 검증한다. */
    private static WorkerProperties props() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application-worker.yml", new ClassPathResource("application-worker.yml"));
        for (PropertySource<?> ps : loaded) {
            env.getPropertySources().addLast(ps);
        }
        return Binder.get(env).bind("netis-maker.worker", WorkerProperties.class).get();
    }

    /** 반려 이력이 있는 디자인 작업. 옛 서버가 보내던 Claude Design 값(designUrl·프로젝트 ID)도 채워 둔다. */
    private static WorkerTaskResponse designTask() {
        return new WorkerTaskResponse(7L, "acme/widgets", "main", "Add login", "desc",
                WorkerTaskResponse.Kind.DESIGN, List.of(), "분석", "[]", null, null, List.of(),
                null, null, "# 이전 설계", "[]", "[{\"feedback\":\"버튼을 더 크게\"}]",
                "https://claude.ai/design/p/abc", "ds-1", "out-1", null, null, null,
                null, null, null, null, null, null, null, List.of());
    }

    @Test
    void the_design_prompt_no_longer_mentions_claude_design_and_leaves_no_placeholder() throws Exception {
        String prompt = WorkerMainLoop.renderDesignPrompt(props().designPromptTemplate(), designTask());

        assertThat(prompt).doesNotContain("DesignSync").doesNotContain("Claude Design")
                .doesNotContain("design-login").doesNotContain("ERROR.txt")
                .doesNotContain("ds-1").doesNotContain("out-1");
        assertThat(prompt).doesNotContainPattern("\\{[a-z_]+\\}");
        assertThat(prompt).contains(".design-out/screens/").contains(".design-out/DESIGN.md")
                .contains(".design-out/result.json")
                .contains("기기 프레임·고정 폭 캔버스 없이");
    }

    @Test
    void the_design_prompt_still_carries_the_task_and_the_rejection_history() throws Exception {
        String prompt = WorkerMainLoop.renderDesignPrompt(props().designPromptTemplate(), designTask());

        assertThat(prompt).contains("Add login").contains("desc")          // {title}, {description}
                .contains("# 이전 설계")                                    // {previous_design_markdown}
                .contains("버튼을 더 크게");                                 // {feedback_history}
    }

    @Test
    void the_pr_design_line_points_to_the_symphony_card_without_a_link() {
        String body = WorkerMainLoop.renderPrBody(designTask(), 12_000, "abcdef1234567", null, null);

        assertThat(body).contains("- **디자인**: 확정 디자인 기반 구현 (Symphony 작업 #7 디자인 카드 참고)\n")
                .doesNotContain("Claude Design").doesNotContain("claude.ai/design");
    }
}
