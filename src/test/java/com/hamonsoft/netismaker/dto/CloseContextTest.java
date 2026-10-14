package com.hamonsoft.netismaker.dto;

import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import com.hamonsoft.netismaker.entity.TaskStatus;
import com.hamonsoft.netismaker.entity.TaskStatusHistory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 종료 노트 재료의 길이 상한 — 프롬프트·claim 응답 크기를 묶는다. */
class CloseContextTest {

    private static Task task(String implLog) {
        Task t = Task.create("a/b", "main", "T", "d", "u", 3, List.of(), "claude-opus-5-5", "high");
        t.setImplementationLog(implLog);
        return t;
    }

    @Test
    void 긴_플랜은_앞부분만_상한까지_싣고_생략_표시를_붙인다() {
        String plan = "P".repeat(20_000);
        CloseContext c = CloseContext.of(task(null), plan, null, List.of(), List.of());
        assertThat(c.planMarkdown()).startsWith("P".repeat(CloseContext.PLAN_MAX)).endsWith("\n…(이하 생략)");
        assertThat(c.planMarkdown().length()).isEqualTo(CloseContext.PLAN_MAX + "\n…(이하 생략)".length());
    }

    @Test
    void 짧은_본문은_그대로_null은_null() {
        CloseContext c = CloseContext.of(task("log"), "plan", null, List.of(), List.of());
        assertThat(c.planMarkdown()).isEqualTo("plan");
        assertThat(c.analysisMarkdown()).isNull();
        assertThat(c.implementationTail()).isEqualTo("log");
        assertThat(CloseContext.of(task(null), null, null, List.of(), List.of()).planMarkdown()).isNull();
    }

    @Test
    void 분석은_앞부분_구현_로그는_뒷부분을_상한까지_싣는다() {
        TaskAnalysis a = TaskAnalysis.create(1L, "A".repeat(20_000), "[]", null, null);
        String log = "x".repeat(10_000) + "END";
        CloseContext c = CloseContext.of(task(log), null, a, List.of(), List.of());
        assertThat(c.analysisMarkdown()).startsWith("A".repeat(CloseContext.ANALYSIS_MAX)).endsWith("\n…(이하 생략)");
        assertThat(c.analysisMarkdown().length()).isEqualTo(CloseContext.ANALYSIS_MAX + "\n…(이하 생략)".length());
        assertThat(c.implementationTail()).startsWith("(앞부분 생략)…\n").endsWith("END");
        assertThat(c.implementationTail().length()).isEqualTo("(앞부분 생략)…\n".length() + CloseContext.IMPL_TAIL_MAX);
    }

    @Test
    void 서로게이트_쌍_한가운데서_자르지_않는다() {
        String emoji = "\uD83D\uDE00"; // 2 chars
        String head = CloseContext.head(emoji.repeat(10), 5); // 5번째 char는 high surrogate
        assertThat(head).startsWith(emoji.repeat(2)).doesNotContain("\uD83D\n");
        String tail = CloseContext.tail(emoji.repeat(10), 5); // 시작 char가 low surrogate
        assertThat(tail).doesNotContain("\n\uDE00");
    }

    @Test
    void 이력은_주어진_순서_그대로_행위자는_없으면_유형으로() {
        TaskStatusHistory h = TaskStatusHistory.log(1L, TaskStatus.DEPLOYED, TaskStatus.CLOSE_REVIEW, "system", null, "r");
        CloseContext c = CloseContext.of(task(null), null, null, List.of(h), List.of());
        assertThat(c.history()).singleElement().satisfies(l -> {
            assertThat(l.from()).isEqualTo("배포완료");
            assertThat(l.to()).isEqualTo("종료승인대기");
            assertThat(l.actor()).isEqualTo("system");
        });
    }
}
