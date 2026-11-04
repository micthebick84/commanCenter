package com.hamonsoft.netismaker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 앱 로그 설정(app.app-log.*)이 값이 빠졌을 때 기본값으로 바인딩되고, 1 미만 정수는 기동 실패로 드러나는지. */
class AppLogPropertiesBindingTest {

    private static AppLogProperties bind(Map<String, String> src) {
        return new Binder(new MapConfigurationPropertySource(src))
                .bindOrCreate("app.app-log", AppLogProperties.class);
    }

    @Test
    void 값이_없으면_기본값() {
        AppLogProperties p = bind(Map.of());
        assertThat(p.enabled()).isTrue();
        assertThat(p.dockerPath()).isEmpty();
        assertThat(p.tailLines()).isEqualTo(500);
        assertThat(p.maxStreams()).isEqualTo(6);
        assertThat(p.maxStreamsPerTask()).isEqualTo(3);
        assertThat(p.maxDurationMinutes()).isEqualTo(30);
        assertThat(p.pingSeconds()).isEqualTo(10);
        assertThat(p.batchMillis()).isEqualTo(300);
        assertThat(p.batchLines()).isEqualTo(200);
        assertThat(p.maxLineChars()).isEqualTo(4000);
    }

    @Test
    void 키를_바꾸면_반영() {
        AppLogProperties p = bind(Map.of(
                "app.app-log.max-streams", "2",
                "app.app-log.docker-path", "C:/x/docker.exe"));
        assertThat(p.maxStreams()).isEqualTo(2);
        assertThat(p.dockerPath()).isEqualTo("C:/x/docker.exe");
        // 안 바꾼 칸은 기본값 그대로
        assertThat(p.maxStreamsPerTask()).isEqualTo(3);
        assertThat(p.enabled()).isTrue();
    }

    @Test
    void 일_미만이면_바인딩_실패() {
        assertThatThrownBy(() -> bind(Map.of("app.app-log.ping-seconds", "0")))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.app-log.ping-seconds는 1 이상이어야 합니다");
    }

    @Test
    void 정수_칸_여덟_개_모두_일_미만이면_자기_이름을_밝히며_실패() {
        List<String> keys = List.of("tail-lines", "max-streams", "max-streams-per-task", "max-duration-minutes",
                "ping-seconds", "batch-millis", "batch-lines", "max-line-chars");
        for (String key : keys) {
            for (String v : new String[]{"0", "-5"}) {
                assertThatThrownBy(() -> bind(Map.of("app.app-log." + key, v)))
                        .as("%s=%s", key, v)
                        .isInstanceOf(BindException.class)
                        .rootCause()
                        .hasMessage("app.app-log." + key + "는 1 이상이어야 합니다");
            }
        }
    }

    @Test
    void application_yml이_같은_키로_같은_값을_싣는다() throws Exception {
        // 키 이름이 틀리면 바인딩은 조용히 기본값으로 돌아간다 — yml에 실제로 그 키가 있는지, 값이 기본값과 같은지 본다.
        StandardEnvironment env = new StandardEnvironment();
        for (PropertySource<?> ps : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(ps);
        }
        // 이 PC에 같은 이름의 환경변수가 있어도 시험이 흔들리지 않게 placeholder 값을 고정한다
        env.getPropertySources().addFirst(new MapPropertySource("t",
                Map.of("APP_LOG_ENABLED", "true", "APP_LOG_DOCKER_PATH", "")));

        for (String key : List.of("enabled", "docker-path", "tail-lines", "max-streams", "max-streams-per-task",
                "max-duration-minutes", "ping-seconds", "batch-millis", "batch-lines", "max-line-chars")) {
            assertThat(env.containsProperty("app.app-log." + key)).as("yml에 app.app-log.%s", key).isTrue();
        }
        AppLogProperties fromYml = Binder.get(env).bind("app.app-log", AppLogProperties.class).get();
        assertThat(fromYml).isEqualTo(bind(Map.of()));
        // 빌드 로그 SSE 핑(조용한 빌드 구간의 Cloudflare 유휴 끊김 방지)은 앱 로그 핑(10초)과 따로 20초다
        assertThat(env.getProperty("app.deploy-log.sse-ping-interval-ms", Integer.class)).isEqualTo(20000);
    }

    @Test
    void 일은_경계값으로_통과() {
        AppLogProperties p = bind(Map.of("app.app-log.tail-lines", "1", "app.app-log.max-line-chars", "1"));
        assertThat(p.tailLines()).isEqualTo(1);
        assertThat(p.maxLineChars()).isEqualTo(1);
    }
}
