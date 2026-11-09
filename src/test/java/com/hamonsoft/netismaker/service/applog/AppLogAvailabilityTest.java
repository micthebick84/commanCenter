package com.hamonsoft.netismaker.service.applog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hamonsoft.netismaker.config.AppLogProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AppLogAvailabilityTest {

    private static AppLogProperties props(boolean enabled) {
        return new AppLogProperties(enabled, "", 500, 6, 3, 30, 10, 300, 200, 4000);
    }

    @Test
    void 켜짐_줄() {
        var a = new AppLogAvailability(props(true), Optional.of(Path.of("/u/.local/bin/docker")), "ssh://adm@10.1.1.75");
        assertThat(a.active()).isTrue();
        assertThat(a.docker()).contains(Path.of("/u/.local/bin/docker"));
        assertThat(a.startupLine()).startsWith("앱 로그: 켜짐(docker=").endsWith(", DOCKER_HOST=원격)")
                .contains(Path.of("/u/.local/bin/docker").toString())
                .doesNotContain("adm@10.1.1.75");
    }

    @Test
    void 켜짐_줄의_DOCKER_HOST는_로컬과_없음도_나온다() {
        Optional<Path> docker = Optional.of(Path.of("/usr/bin/docker"));
        assertThat(new AppLogAvailability(props(true), docker, "npipe:////./pipe/docker_engine").startupLine())
                .endsWith(", DOCKER_HOST=로컬)");
        assertThat(new AppLogAvailability(props(true), docker, null).startupLine())
                .endsWith(", DOCKER_HOST=없음)");
    }

    @Test
    void 꺼짐_줄() {
        var noDocker = new AppLogAvailability(props(true), Optional.empty(), "ssh://u@h");
        assertThat(noDocker.active()).isFalse();
        assertThat(noDocker.docker()).isEmpty();
        assertThat(noDocker.startupLine()).isEqualTo("앱 로그: 꺼짐(docker 없음)");

        // 설정이 꺼져 있으면 docker가 있어도 꺼짐 — 설정 off가 우선이고 docker는 내놓지 않는다
        var off = new AppLogAvailability(props(false), Optional.of(Path.of("/usr/bin/docker")), "ssh://u@h");
        assertThat(off.active()).isFalse();
        assertThat(off.docker()).isEmpty();
        assertThat(off.startupLine()).isEqualTo("앱 로그: 꺼짐(설정 off)");
    }

    @Test
    void DOCKER_HOST_종류() {
        assertThat(AppLogAvailability.dockerHostKind(null)).isEqualTo("없음");
        assertThat(AppLogAvailability.dockerHostKind("  ")).isEqualTo("없음");
        assertThat(AppLogAvailability.dockerHostKind("")).isEqualTo("없음");
        assertThat(AppLogAvailability.dockerHostKind("npipe:////./pipe/docker_engine")).isEqualTo("로컬");
        assertThat(AppLogAvailability.dockerHostKind("unix:///var/run/docker.sock")).isEqualTo("로컬");
        assertThat(AppLogAvailability.dockerHostKind("  NPIPE:////./pipe/docker_engine ")).isEqualTo("로컬");
        assertThat(AppLogAvailability.dockerHostKind("tcp://x:2375")).isEqualTo("원격");
        assertThat(AppLogAvailability.dockerHostKind("ssh://adm@10.1.1.75")).isEqualTo("원격");
    }

    @Test
    void 스프링이_생성자가_둘이어도_AppLogProperties_하나짜리로_만든다() {
        // 로컬 @SpringBootTest는 RUN_TESTCONTAINERS로 건너뛰어 CI에서야 드러나던 기동 실패(@Autowired 누락)를 여기서 잡는다
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("api");
            ctx.registerBean(AppLogProperties.class, () -> props(false));
            ctx.register(AppLogAvailability.class);
            ctx.refresh();

            assertThat(ctx.getBean(AppLogAvailability.class).active()).isFalse();
        }
    }

    @Test
    void 생성할_때_줄을_한_번_info로_남기고_DOCKER_HOST_값은_싣지_않는다() {
        Logger logger = (Logger) LoggerFactory.getLogger(AppLogAvailability.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var a = new AppLogAvailability(props(true), Optional.of(Path.of("/usr/bin/docker")), "ssh://adm@10.1.1.75");
            a.active();
            a.docker();
            a.startupLine();

            List<ILoggingEvent> events = appender.list;
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getLevel().toString()).isEqualTo("INFO");
            assertThat(events.get(0).getFormattedMessage()).isEqualTo(a.startupLine())
                    .doesNotContain("adm@10.1.1.75");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
