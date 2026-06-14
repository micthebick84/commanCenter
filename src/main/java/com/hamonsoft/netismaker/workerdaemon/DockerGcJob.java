package com.hamonsoft.netismaker.workerdaemon;

import com.hamonsoft.netismaker.workerdaemon.deploy.DeployTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * docker 자원 GC 리퍼 (worker 프로파일). gc-interval-minutes마다 DeployTarget.gc 호출.
 * gc-enabled=false면 no-op. 부팅 직후엔 initialDelay로 한 텀 쉰다(배포 직후 경합 회피).
 */
@Component
@Profile("worker")
@Slf4j
public class DockerGcJob {

    private final DeployTarget target;
    private final WorkerProperties.Deploy cfg;

    public DockerGcJob(DeployTarget target, WorkerProperties props) {
        this.target = target;
        this.cfg = props.deploy();
    }

    @Scheduled(initialDelayString = "#{${netis-maker.worker.deploy.gc-interval-minutes:60} * 60000}",
               fixedRateString = "#{${netis-maker.worker.deploy.gc-interval-minutes:60} * 60000}")
    public void reap() {
        if (Boolean.FALSE.equals(cfg.gcEnabled())) return;
        log.debug("GC 리퍼 시작 (orphanGrace={}m, keepImages={})",
                cfg.gcOrphanGraceMinutes(), cfg.gcKeepImagesPerTask());
        target.gc(cfg.gcOrphanGraceMinutes(), cfg.gcKeepImagesPerTask());
    }
}
