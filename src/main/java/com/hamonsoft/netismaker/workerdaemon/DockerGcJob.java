package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import com.hamonsoft.netismaker.workerdaemon.deploy.DockerGcPlanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * docker 자원 GC 리퍼 (worker 프로파일). gc-interval-minutes마다 DeployTarget.gc 호출.
 * gc-enabled=false면 no-op. 부팅 직후엔 initialDelay로 한 텀 쉰다(배포 직후 경합 회피).
 *
 * 배포완료/배포중단됨 task의 컨테이너는 보호 목록으로 넘겨 제거하지 않는다 —
 * DB가 '배포 살아있음'이라 믿는 컨테이너를 GC가 지워 괴리를 영구화하던 문제의 방지책.
 * 보호 목록 조회 실패 시 이번 주기를 통째로 건너뛴다(fail-closed: 모르면 안 지운다).
 */
@Component
@Profile("worker")
@Slf4j
public class DockerGcJob {

    private final DeployTarget target;
    private final WorkerHttpClient http;
    private final WorkerProperties.Deploy cfg;

    public DockerGcJob(DeployTarget target, WorkerHttpClient http, WorkerProperties props) {
        this.target = target;
        this.http = http;
        this.cfg = props.deploy();
    }

    @Scheduled(initialDelayString = "#{${netis-maker.worker.deploy.gc-interval-minutes:60} * 60000}",
               fixedRateString = "#{${netis-maker.worker.deploy.gc-interval-minutes:60} * 60000}")
    public void reap() {
        if (Boolean.FALSE.equals(cfg.gcEnabled())) return;

        Set<String> protectedContainers;
        try {
            protectedContainers = http.deployedTasks().stream()
                    .map(t -> DockerGcPlanner.OWN_PREFIX + t.id())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("GC 보호 목록 조회 실패 — 이번 주기 스킵 (fail-closed): {}", e.getMessage());
            return;
        }

        log.debug("GC 리퍼 시작 (orphanGrace={}m, keepImages={}, protected={})",
                cfg.gcOrphanGraceMinutes(), cfg.gcKeepImagesPerTask(), protectedContainers.size());
        target.gc(cfg.gcOrphanGraceMinutes(), cfg.gcKeepImagesPerTask(), protectedContainers);
    }
}
