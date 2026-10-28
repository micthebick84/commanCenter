package com.hamonsoft.netismaker.service.render;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** soffice 실행 지점 — 테스트가 가짜 명령(시간 초과·종료 코드)을 넣을 수 있게 분리했다. 기본은 {@link ProcessBuilderStarter}. */
@FunctionalInterface
public interface ProcessStarter {

    Process start(List<String> command, Path workDir) throws IOException;
}
