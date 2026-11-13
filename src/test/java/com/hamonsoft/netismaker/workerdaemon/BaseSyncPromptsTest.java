package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.dto.WorkerTaskResponse;
import com.hamonsoft.netismaker.dto.WorkerTaskResponse.FixRoundSpec;
import com.hamonsoft.netismaker.entity.Task;
import com.hamonsoft.netismaker.entity.TaskAnalysis;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 기준 브랜치 반영 회차의 이름·병합 커밋 메시지·충돌 해결 프롬프트. */
class BaseSyncPromptsTest {

    private static final String BRANCH = "netismaker/task-42-x";
    private static final String TIP = "abc1234" + "0".repeat(33);

    private static FixRoundSpec spec(String kind) {
        return new FixRoundSpec(7L, 3, 1, "기준 브랜치 main 반영", List.of(), BRANCH, TIP, kind);
    }

    private static WorkerTaskResponse task(String plan) {
        Task t = Task.create("acme/widgets", "main", "Add login", "로그인 화면을 만든다", "user1", 3, List.of(),
                "claude-opus-5-5", "high");
        ReflectionTestUtils.setField(t, "id", 42L);
        return WorkerTaskResponse.forImplementation(t,
                TaskAnalysis.create(42L, "# 설계 문서", "[]", null, 1000L), null, plan, spec("BASE_SYNC"), null);
    }

    @Test
    void render는_고정_문구로_시작하고_충돌_파일과_작업_내용을_담는다() {
        String prompt = BaseSyncPrompts.render(task("### 작업 1: 로그인 화면"), spec("BASE_SYNC"), TIP,
                List.of("PLAN.md", "docs/작업 기록.md"));

        assertThat(prompt).startsWith("""
                당신은 git 병합 충돌을 푸는 전문가입니다. 현재 디렉토리는 이 작업 전용 git worktree이고, 작업 브랜치 'netismaker/task-42-x'(끝 커밋 abc1234)에
                기준 브랜치 'main'를 병합하다가 충돌로 멈춘 상태입니다. 충돌하지 않은 변경은 이미 병합되어 스테이징돼 있습니다.

                ## 할 일 — 아래 충돌 파일의 충돌만 푸세요
                - PLAN.md
                - docs/작업 기록.md
                - 충돌 표식(<<<<<<<, =======, >>>>>>>)이 있는 곳만 고치고, 표식은 모두 지우세요.
                """);
        assertThat(prompt).contains("`merge --abort`는 하지 마세요")
                .contains("## 이 작업 (참고용 — 다시 구현하지 마세요)\n\n### 제목\nAdd login\n\n### 요구사항\n로그인 화면을 만든다")
                .contains("### 확정된 구현 계획\n### 작업 1: 로그인 화면");
    }

    @Test
    void render는_리뷰_배포env_DB_절을_붙이지_않는다() {
        String prompt = BaseSyncPrompts.render(task("### 작업 1"), spec("BASE_SYNC"), TIP, List.of("PLAN.md"));

        assertThat(prompt).doesNotContain("리뷰").doesNotContain(".netis-deploy-env.json").doesNotContain("mcp__db-");
    }

    @Test
    void 계획이_없으면_계획_절을_붙이지_않는다() {
        String prompt = BaseSyncPrompts.render(task(null), spec("BASE_SYNC"), TIP, List.of("PLAN.md"));

        assertThat(prompt).doesNotContain("확정된 구현 계획");
    }

    @Test
    void commitMessage는_기준_브랜치와_제목_task_번호_공저자를_담는다() {
        assertThat(BaseSyncPrompts.commitMessage(task(null), "bot@example.com"))
                .isEqualTo("merge: 기준 브랜치 main 반영 — Add login\n\ntask #42\n\nCo-Authored-By: netisMaker <bot@example.com>");
    }

    @Test
    void label은_회차_번호를_담는다() {
        assertThat(BaseSyncPrompts.label(spec("BASE_SYNC"))).isEqualTo("기준 브랜치 반영 3회차");
    }

    @Test
    void isBaseSync는_BASE_SYNC_종류만_참이다() {
        assertThat(BaseSyncPrompts.isBaseSync(spec("BASE_SYNC"))).isTrue();
        assertThat(BaseSyncPrompts.isBaseSync(spec("FIX"))).isFalse();
        assertThat(BaseSyncPrompts.isBaseSync(spec(null))).isFalse();
        assertThat(BaseSyncPrompts.isBaseSync(null)).isFalse();
    }
}
