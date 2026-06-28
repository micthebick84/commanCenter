package com.hamonsoft.netismaker.workerdaemon.deploy;

import java.nio.file.Path;
import java.util.Map;

/**
 * 배포 호스트 추상화. 워커는 docker를 직접 호출하지 않고 이 인터페이스만 사용한다.
 *
 *  MVP 구현: {@link LocalDockerTarget} (워커 로컬 docker 데몬).
 *  확장: RemoteSshDockerTarget / RegistryDeployTarget — 같은 인터페이스로 교체.
 *        설정 netis-maker.worker.deploy.target 으로 주입 선택.
 */
public interface DeployTarget {

    /** 빌드 + 실행. logSink로 빌드/헬스 로그를 라인 단위 스트리밍한다(없으면 무시 가능). */
    DeployResult deploy(DeploySpec spec, java.util.function.Consumer<String> logSink) throws Exception;

    /** 컨테이너 중지 + 제거 (멱등 — 없으면 무시). */
    void stop(String containerName) throws Exception;

    /** 컨테이너 상태 조회 (보고용). */
    DeployStatus status(String containerName);

    /**
     * 자가정리: grace 지난 비실행 owned 컨테이너 + 보존 외 owned 이미지 제거.
     * 원격 타깃 등 미지원 구현은 no-op (default).
     */
    default void gc(int orphanGraceMinutes, int keepImagesPerTask) { }

    /**
     * @param taskId        작업 ID (공개 배포 라우팅 슬러그 등에 사용)
     * @param contextDir    빌드 컨텍스트(Dockerfile 포함 worktree)
     * @param imageName     예: netis-task-7:abcdef1
     * @param containerName 예: netis-task-7
     * @param containerPort 컨테이너 내부 LISTEN 포트
     * @param env           컨테이너 환경변수
     * @param labels        docker 라벨 (예: netis-maker.task=7)
     */
    record DeploySpec(
            long taskId,
            Path contextDir,
            String imageName,
            String containerName,
            int containerPort,
            Map<String, String> env,
            Map<String, String> labels
    ) {}

    /** @param log build+run 합본 출력 tail. */
    record DeployResult(String url, String containerId, int hostPort, String image, String log) {}

    enum DeployStatus { RUNNING, STOPPED, UNKNOWN }
}
