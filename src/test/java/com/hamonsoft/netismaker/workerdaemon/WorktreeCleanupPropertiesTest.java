package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorktreeCleanupPropertiesTest {

    private static WorkerProperties bind(Map<String, Object> env) throws Exception {
        StandardEnvironment e = new StandardEnvironment();
        for (PropertySource<?> ps : new YamlPropertySourceLoader()
                .load("application-worker.yml", new ClassPathResource("application-worker.yml"))) {
            e.getPropertySources().addLast(ps);
        }
        e.getPropertySources().addFirst(new MapPropertySource("t", env));
        return Binder.get(e).bind("netis-maker.worker", WorkerProperties.class).get();
    }

    @Test
    void defaults_are_enabled_0230_and_seven_days() throws Exception {
        WorkerProperties.WorktreeCleanup c = bind(Map.of()).worktreeCleanup();
        assertThat(c.enabled()).isTrue();
        assertThat(c.cron()).isEqualTo("0 30 2 * * *");
        assertThat(c.retentionDays()).isEqualTo(7);
    }

    @Test
    void env_overrides_bind() throws Exception {
        WorkerProperties.WorktreeCleanup c = bind(Map.of(
                "WORKTREE_CLEANUP_ENABLED", "false",
                "WORKTREE_CLEANUP_CRON", "-",
                "WORKTREE_CLEANUP_RETENTION_DAYS", "14")).worktreeCleanup();
        assertThat(c.enabled()).isFalse();
        assertThat(c.cron()).isEqualTo("-");
        assertThat(c.retentionDays()).isEqualTo(14);
    }

    @Test
    void blank_values_fall_back_to_defaults() throws Exception {
        WorkerProperties.WorktreeCleanup c = bind(Map.of(
                "WORKTREE_CLEANUP_CRON", "",
                "WORKTREE_CLEANUP_RETENTION_DAYS", "")).worktreeCleanup();
        assertThat(c.cron()).isEqualTo("0 30 2 * * *");
        assertThat(c.retentionDays()).isEqualTo(7);
    }

    @Test
    void zero_or_negative_retention_fails_binding_naming_the_setting() {
        for (String v : new String[]{"0", "-3"}) {
            assertThatThrownBy(() -> bind(Map.of("WORKTREE_CLEANUP_RETENTION_DAYS", v)))
                    .isInstanceOf(BindException.class)
                    .rootCause().hasMessageContaining("WORKTREE_CLEANUP_RETENTION_DAYS");
        }
    }

    @Test
    void invalid_cron_fails_binding_naming_the_setting() {
        assertThatThrownBy(() -> bind(Map.of("WORKTREE_CLEANUP_CRON", "every night")))
                .isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("WORKTREE_CLEANUP_CRON");
    }

    @Test
    void absent_block_gets_defaults_in_the_record_itself() {
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, null);
        WorkerProperties p = new WorkerProperties("w1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, deploy, null, null);
        assertThat(p.worktreeCleanup().retentionDays()).isEqualTo(7);
    }
}
