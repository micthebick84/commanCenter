package com.hamonsoft.netismaker.service.applog;

import com.hamonsoft.netismaker.util.StackSecretEnv;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 기본 {@link DockerLogsLauncher} — docker logs를 stdout·stderr 둘 다 PIPE로 띄우고 stdin은 바로 닫는다.
 * 짝은 render의 ProcessBuilderStarter지만 재사용하지 않는다 — 그쪽은 stderr를 합쳐 파일로 보내는데, 여기서는 줄마다
 * out/err를 가르려고 두 스트림을 따로 읽는다(안 읽으면 파이프가 차는 순간 docker가 멈춘다 — 읽는 쪽은 AppLogStream).
 * docker가 원격 데몬에 닿는 데 DOCKER_HOST가 필요하므로 env는 그대로 물려주되 스택 비밀({@link StackSecretEnv})만 뺀다.
 * render의 ProcessStarter를 구현하지 않는다 — 구현하면 SlideRenderer의 생성자 주입이 모호해져 API가 못 뜬다.
 */
@Component
@Profile("api")
public class ProcessDockerLogsLauncher implements DockerLogsLauncher {

    @Override
    public Process start(List<String> command) throws IOException {
        Process p = builder(command, System.getenv()).start();
        try {
            p.getOutputStream().close();
        } catch (IOException | RuntimeException e) {
            p.destroyForcibly();
            throw e;
        }
        return p;
    }

    /** 자식 env = parentEnv − 스택 비밀. 테스트가 실행 없이 env·리다이렉트를 볼 수 있게 분리했다. */
    static ProcessBuilder builder(List<String> command, Map<String, String> parentEnv) {
        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE);
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(parentEnv);
        StackSecretEnv.strip(env);
        return pb;
    }
}
