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

    /** 컨테이너 상태 조회 (reconcile/보고용). 재시작 대기(restarting)는 RUNNING으로 본다. */
    DeployStatus status(String containerName);

    /**
     * 자가정리: grace 지난 비실행 owned 컨테이너 + 보존 외 owned 이미지 제거.
     * protectedContainers(배포완료/배포중단됨 task의 컨테이너)는 제거 대상에서 제외.
     * 원격 타깃 등 미지원 구현은 no-op (default).
     */
    default void gc(int orphanGraceMinutes, int keepImagesPerTask,
                    java.util.Set<String> protectedContainers) { }

    /**
     * 베이스 이미지(FROM)가 빌드 때 받을 수 있는지 미리 확인한다. MISSING은 "레지스트리에 그 태그가 없다"가 확실할 때만 —
     * 인증 거부·네트워크 오류·미지원 구현은 UNKNOWN으로 두고 판단을 docker build에 맡긴다.
     */
    default ImageAvailability checkImage(String image) {
        return ImageAvailability.UNKNOWN;
    }

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
            Map<String, String> labels,
            Map<String, String> buildArgs   // docker build --build-arg (스펙 2026-11-04 §7, 예: NETIS_GIT_SHA)
    ) {
        public DeploySpec {
            buildArgs = buildArgs == null ? Map.of() : buildArgs;
        }
    }

    /**
     * @param log      build+run 합본 출력 tail.
     * @param builtSha 실제로 빌드한 커밋(전체 40자) — DeployService가 deploy worktree의 HEAD로 채운다(스펙 2026-11-03 §7).
     *                 타깃은 모르므로 null, 못 읽었을 때도 null.
     */
    record DeployResult(String url, String containerId, int hostPort, String image, String log, String builtSha) {
        /** 빌드 커밋 없는 결과 — 타깃 구현·기존 호출부. */
        public DeployResult(String url, String containerId, int hostPort, String image, String log) {
            this(url, containerId, hostPort, image, log, null);
        }
    }

    enum DeployStatus { RUNNING, STOPPED, UNKNOWN }

    enum ImageAvailability { PRESENT, MISSING, UNKNOWN }
}
