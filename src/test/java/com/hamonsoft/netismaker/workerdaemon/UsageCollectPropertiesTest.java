package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageCollectPropertiesTest {

    private static StandardEnvironment env(Map<String, Object> overrides) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application-worker.yml", new ClassPathResource("application-worker.yml"));
        for (PropertySource<?> ps : loaded) env.getPropertySources().addLast(ps);
        env.getPropertySources().addFirst(new MapPropertySource("test-env-overrides", overrides));
        return env;
    }

    private static UsageCollectProperties bind(Map<String, Object> overrides) throws Exception {
        return Binder.get(env(overrides)).bind("netis-maker.worker.usage-collect", UsageCollectProperties.class).get();
    }

    @Test
    void 모두_null이면_기본값() {
        UsageCollectProperties p = new UsageCollectProperties(null, null, null, null, null, null, null);
        assertThat(p.enabled()).isTrue();
        assertThat(p.intervalSeconds()).isEqualTo(300);
        assertThat(p.includeDirect()).isFalse();
        assertThat(p.symphonyRoots()).isEmpty();
        assertThat(p.countFromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.machine()).isNull();
        assertThat(p.maxBytesPerRun()).isEqualTo(256L * 1024 * 1024);
    }

    @Test
    void yml_기본값과_env_재정의() throws Exception {
        UsageCollectProperties defaults = bind(Map.of());
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.intervalSeconds()).isEqualTo(300);
        assertThat(defaults.includeDirect()).isFalse();
        assertThat(defaults.symphonyRoots()).isEmpty();
        assertThat(defaults.machine()).isNull();

        UsageCollectProperties p = bind(Map.of(
                "USAGE_COLLECT_INCLUDE_DIRECT", "true",
                "USAGE_COLLECT_SYMPHONY_ROOTS", " C:\\netis-worker , ,~/netis-remote-worker",
                "USAGE_COLLECT_MACHINE", " pc-1 "));
        assertThat(p.includeDirect()).isTrue();
        assertThat(p.symphonyRoots()).containsExactly("C:\\netis-worker", "~/netis-remote-worker");
        assertThat(p.machine()).isEqualTo("pc-1");
    }

    @Test
    void 빈_env는_기본값() throws Exception {
        UsageCollectProperties p = bind(Map.of(
                "USAGE_COLLECT_INTERVAL_SECONDS", "",
                "USAGE_COLLECT_MACHINE", "",
                "USAGE_COLLECT_COUNT_FROM", "",
                "USAGE_COLLECT_MAX_MB_PER_RUN", ""));
        assertThat(p.intervalSeconds()).isEqualTo(300);
        assertThat(p.machine()).isNull();
        assertThat(p.countFromDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(p.maxMbPerRun()).isEqualTo(256);
    }

    @Test
    void 잘못된_값은_env_이름과_함께() {
        assertThatThrownBy(() -> new UsageCollectProperties(null, 10, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("USAGE_COLLECT_INTERVAL_SECONDS");
        assertThatThrownBy(() -> new UsageCollectProperties(null, null, null, null, "2026/10/01", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("USAGE_COLLECT_COUNT_FROM");
        assertThatThrownBy(() -> new UsageCollectProperties(null, null, null, null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("USAGE_COLLECT_MAX_MB_PER_RUN");
    }
}
