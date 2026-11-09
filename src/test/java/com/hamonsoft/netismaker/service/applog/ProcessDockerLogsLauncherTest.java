package com.hamonsoft.netismaker.service.applog;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessDockerLogsLauncherTest {

    @Test
    void 자식_env에서_스택_비밀을_빼고_DOCKER_HOST는_남긴다() {
        List<String> cmd = List.of("docker", "logs", "--tail", "500", "--timestamps", "--follow", "netis-task-7");

        ProcessBuilder pb = ProcessDockerLogsLauncher.builder(cmd, Map.of(
                "WORKER_API_KEY", "k", "Spring_Datasource_Password", "p", "DOCKER_HOST", "ssh://u@h", "PATH", "x"));

        assertThat(pb.environment().keySet()).noneMatch(k -> k.equalsIgnoreCase("WORKER_API_KEY"));
        assertThat(pb.environment().keySet()).noneMatch(k -> k.equalsIgnoreCase("SPRING_DATASOURCE_PASSWORD"));
        assertThat(pb.environment()).containsEntry("DOCKER_HOST", "ssh://u@h").containsEntry("PATH", "x");
        assertThat(pb.command()).containsExactlyElementsOf(cmd);
    }

    @Test
    void stdout과_stderr는_합치지_않고_둘_다_PIPE() {
        ProcessBuilder pb = ProcessDockerLogsLauncher.builder(List.of("docker", "logs"), Map.of("PATH", "x"));

        assertThat(pb.redirectErrorStream()).isFalse();
        assertThat(pb.redirectOutput()).isEqualTo(ProcessBuilder.Redirect.PIPE);
        assertThat(pb.redirectError()).isEqualTo(ProcessBuilder.Redirect.PIPE);
    }

    @Test
    void 부모_env에_없는_변수는_자식에게_새로_생기지_않는다() {
        // 이 시험 JVM의 env가 새어 들어오면 안 된다 — clear 뒤 부모로 넘긴 값만 있다
        ProcessBuilder pb = ProcessDockerLogsLauncher.builder(List.of("docker"), Map.of("ONLY", "1"));

        assertThat(pb.environment()).containsOnlyKeys("ONLY");
    }

    @Test
    void render_ProcessStarter를_구현하지_않는다() throws Exception {
        // SlideRenderer 생성자 주입이 ProcessStarter 빈을 하나로 찾는다 — 둘이 되면 모호해져 API 기동이 실패한다
        Class<?> starter = Class.forName("com.hamonsoft.netismaker.service.render.ProcessStarter");
        assertThat(starter.isAssignableFrom(ProcessDockerLogsLauncher.class)).isFalse();
        assertThat(DockerLogsLauncher.class.isAssignableFrom(ProcessDockerLogsLauncher.class)).isTrue();
    }

    @Test
    void 두_실행기가_함께_빈이어도_각_타입의_후보는_하나() throws Exception {
        Class<?> starter = Class.forName("com.hamonsoft.netismaker.service.render.ProcessStarter");
        Class<?> builderStarter = Class.forName("com.hamonsoft.netismaker.service.render.ProcessBuilderStarter");
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().setActiveProfiles("api");
            ctx.register(builderStarter, ProcessDockerLogsLauncher.class);
            ctx.refresh();

            assertThat(ctx.getBeansOfType(starter)).hasSize(1);
            assertThat(ctx.getBeansOfType(DockerLogsLauncher.class)).hasSize(1);
        }
    }
}
