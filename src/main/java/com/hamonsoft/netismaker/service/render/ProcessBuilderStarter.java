package com.hamonsoft.netismaker.service.render;

import com.hamonsoft.netismaker.util.StackSecretEnv;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 기본 {@link ProcessStarter} — 작업 폴더를 cwd로, stdout·stderr를 작업 폴더의 {@value #LOG_NAME}로 보낸다.
 * 파이프로 두면 아무도 읽지 않아 버퍼가 차는 순간 soffice가 멈춘다. stdin은 바로 닫는다.
 * soffice는 신뢰할 수 없는 첨부를 열므로 스택 비밀 env({@link StackSecretEnv})는 넘기지 않는다.
 */
@Component
@Profile("api")
public class ProcessBuilderStarter implements ProcessStarter {

    static final String LOG_NAME = "soffice.log";

    @Override
    public Process start(List<String> command, Path workDir) throws IOException {
        Process p = builder(command, workDir, System.getenv()).start();
        p.getOutputStream().close();
        return p;
    }

    /** 자식 env = parentEnv − 스택 비밀. 테스트가 실행 없이 env를 볼 수 있게 분리했다. */
    static ProcessBuilder builder(List<String> command, Path workDir, Map<String, String> parentEnv) {
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(workDir.resolve(LOG_NAME).toFile());
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(parentEnv);
        StackSecretEnv.strip(env);
        return pb;
    }
}
