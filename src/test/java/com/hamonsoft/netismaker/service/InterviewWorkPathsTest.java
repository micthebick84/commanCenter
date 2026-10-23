package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // 정리 잡의 스캔이 이 이름들로 폴더를 찾는다 — 경로를 만드는 쪽과 한 곳에서 읽는다
    @Test
    void scan_names_are_the_ones_the_paths_are_built_from() {
        InterviewWorkPaths p = new InterviewWorkPaths("/r");
        assertThat(p.workDirFor("owner/repo", null, 7)).endsWith("/" + InterviewWorkPaths.SESSION_DIR_PREFIX + "7");
        assertThat(p.refDir(7, 2)).isEqualTo("/r/" + InterviewWorkPaths.REFS_DIR + "/7/2");
    }

    // ── 빈 루트는 기동 때 거부한다 — 그대로 두면 "/session-1"처럼 파일시스템 루트 아래 경로가 만들어진다 ──

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "  \n "})
    void blank_root_is_rejected(String root) {
        assertThatThrownBy(() -> new InterviewWorkPaths(root)).isInstanceOf(IllegalArgumentException.class);
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
    void blank_work_root_property_fails_the_context() {
        // app.interview.work-root= (빈 값)은 @Value 기본값으로 대체되지 않는다 → 생성자가 거부해 기동이 실패한다
        runner().withPropertyValues("app.interview.work-root=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().rootCause().isInstanceOf(IllegalArgumentException.class);
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
