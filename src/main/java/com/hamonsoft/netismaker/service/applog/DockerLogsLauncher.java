package com.hamonsoft.netismaker.service.applog;

import java.io.IOException;
import java.util.List;

/**
 * docker logs 실행 지점 — 단위 시험이 가짜 프로세스를 넣을 수 있게 분리했다. 기본은 {@link ProcessDockerLogsLauncher}.
 * render의 ProcessStarter와는 별개 타입이다(stdout·stderr를 읽어야 하고 cwd가 없다).
 */
@FunctionalInterface
public interface DockerLogsLauncher {

    /** command를 실행한 프로세스를 돌려준다. 기본 구현은 stdout·stderr를 PIPE로 두고 stdin을 이미 닫아서 돌려준다. */
    Process start(List<String> command) throws IOException;
}
