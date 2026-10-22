package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 인터뷰·질문 체크아웃 경로 규칙 — 대상 work_dir는 기존 형식 그대로, 참조는 짧은 별도 경로 (스펙 2026-10-22 §3.3). */
class InterviewWorkPathsTest {

    @Test
    void work_dir_keeps_the_legacy_format() {
        InterviewWorkPaths p = new InterviewWorkPaths("/home/u/netis-maker/interviews");
        assertThat(p.workDirFor("owner/repo", null, 1)).isEqualTo("/home/u/netis-maker/interviews/owner/repo/session-1");
        assertThat(p.workDirFor("g/sub/p", "https://gitlab.example/g/sub/p.git", 7))
                .isEqualTo("/home/u/netis-maker/interviews/_gitlab/g+sub+p/session-7");
    }

    @Test
    void one_level_key_gets_the_defensive_prefix() {
        // 레거시 이상 데이터(owner/repo가 아닌 한 단계 값)도 항상 2단계 아래에 둔다
        assertThat(new InterviewWorkPaths("/r").workDirFor("solo", null, 3)).isEqualTo("/r/_/solo/session-3");
    }

    @Test
    void ref_dir_is_short_and_separate_from_the_target() {
        assertThat(new InterviewWorkPaths("/r").refDir(123, 12)).isEqualTo("/r/_refs/123/12");
    }

    @Test
    void root_is_the_configured_checkout_root() {
        assertThat(new InterviewWorkPaths("/r").root()).isEqualTo(Path.of("/r"));
    }

    // ── 설정 연결: app.interview.work-root 하나가 대상·참조·정리 잡의 기준 루트다 ──

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=api")
                .withUserConfiguration(InterviewWorkPaths.class);
    }

    @Test
    void default_root_is_the_legacy_location_under_user_home() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            String legacyRoot = System.getProperty("user.home") + "/netis-maker/interviews";
            InterviewWorkPaths p = ctx.getBean(InterviewWorkPaths.class);
            assertThat(p.workDirFor("owner/repo", null, 1)).isEqualTo(legacyRoot + "/owner/repo/session-1");
            assertThat(p.root()).isEqualTo(Path.of(legacyRoot));
        });
    }

    @Test
    void work_root_property_moves_every_path_together() {
        runner().withPropertyValues("app.interview.work-root=/data/interviews").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            InterviewWorkPaths p = ctx.getBean(InterviewWorkPaths.class);
            assertThat(p.workDirFor("owner/repo", null, 1)).isEqualTo("/data/interviews/owner/repo/session-1");
            assertThat(p.refDir(1, 2)).isEqualTo("/data/interviews/_refs/1/2");
            assertThat(p.root()).isEqualTo(Path.of("/data/interviews"));
        });
    }
}
