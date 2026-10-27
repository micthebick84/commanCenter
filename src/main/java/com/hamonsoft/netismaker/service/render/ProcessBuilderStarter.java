package com.hamonsoft.netismaker.service.render;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 기본 {@link ProcessStarter} — 작업 폴더를 cwd로, stdout·stderr를 작업 폴더의 {@value #LOG_NAME}로 보낸다.
 * 파이프로 두면 아무도 읽지 않아 버퍼가 차는 순간 soffice가 멈춘다. stdin은 바로 닫는다.
 */
@Component
@Profile("api")
public class ProcessBuilderStarter implements ProcessStarter {

    static final String LOG_NAME = "soffice.log";

    @Override
    public Process start(List<String> command, Path workDir) throws IOException {
        Process p = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(workDir.resolve(LOG_NAME).toFile())
                .start();
        p.getOutputStream().close();
        return p;
    }
}
