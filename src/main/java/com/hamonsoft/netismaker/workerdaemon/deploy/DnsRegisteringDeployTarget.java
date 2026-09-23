package com.hamonsoft.netismaker.workerdaemon.deploy;

import lombok.extern.slf4j.Slf4j;

import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공개 배포 DNS 수명주기를 배포 타깃에 얹는 데코레이터 (provider=cloudflare일 때만 등록 — PublicDnsConfiguration).
 *
 *  deploy: 컨테이너가 헬스체크를 통과한 뒤 DNS upsert. 실패하면 컨테이너를 내리고 배포실패로 던진다 —
 *          열리지 않는 URL을 배포완료로 기록하지 않기 위해. upsert는 멱등이라 재배포로 복구된다.
 *  stop:   컨테이너 제거 뒤 DNS delete. 실패는 경고만(남은 레코드는 gc가 회수).
 *  gc:     컨테이너 GC 뒤, 이 워커가 만든 레코드 중 보호 목록에 없고 컨테이너가 STOPPED인 것만 삭제.
 *          RUNNING(배포 직후 보고 전 경합)·UNKNOWN(데몬 불통)은 보존한다.
 *
 * DeployService·DockerGcJob·DeployReconcileJob은 DeployTarget만 알므로 이 클래스를 몰라도 된다.
 */
@Slf4j
public class DnsRegisteringDeployTarget implements DeployTarget {

    private static final Pattern OWN_CONTAINER =
            Pattern.compile(Pattern.quote(DockerGcPlanner.OWN_PREFIX) + "(\\d{1,18})");

    private final DeployTarget delegate;
    private final PublicDnsRegistrar dns;
    private final String baseDomain;
    private final String slugSuffix;

    public DnsRegisteringDeployTarget(DeployTarget delegate, PublicDnsRegistrar dns,
                                      String baseDomain, String slugSuffix) {
        this.delegate = delegate;
        this.dns = dns;
        this.baseDomain = baseDomain;
        this.slugSuffix = slugSuffix;
    }

    @Override
    public DeployResult deploy(DeploySpec spec, Consumer<String> logSink) throws Exception {
        Consumer<String> sink = logSink == null ? (s -> {}) : logSink;
        DeployResult r = delegate.deploy(spec, sink);
        String baseLog = r.log() == null ? "" : r.log();
        String host = PublicRoute.hostname(spec.taskId(), baseDomain, slugSuffix);
        try {
            dns.upsert(host);
        } catch (Exception e) {
            String line = "[공개 주소 DNS 등록 실패: " + host + " — " + e.getMessage() + "]";
            sink.accept(line);
            try {
                delegate.stop(spec.containerName());
            } catch (Exception stopErr) {
                log.warn("DNS 등록 실패 후 컨테이너 정리 실패 ({}): {}", spec.containerName(), stopErr.getMessage());
            }
            throw new DeployFailedException("공개 주소 DNS 등록 실패: " + e.getMessage(),
                    baseLog + "\n" + line + "\n");
        }
        String line = "[공개 주소 DNS 등록: " + host + "]";
        sink.accept(line);
        return new DeployResult(r.url(), r.containerId(), r.hostPort(), r.image(), baseLog + "\n" + line + "\n");
    }

    @Override
    public void stop(String containerName) throws Exception {
        delegate.stop(containerName);
        OptionalLong id = taskIdOfContainer(containerName);
        if (id.isEmpty()) return;
        String host = PublicRoute.hostname(id.getAsLong(), baseDomain, slugSuffix);
        try {
            dns.delete(host);
        } catch (Exception e) {
            log.warn("공개 주소 DNS 삭제 실패 (다음 GC에서 재시도): {} — {}", host, e.getMessage());
        }
    }

    @Override
    public DeployStatus status(String containerName) {
        return delegate.status(containerName);
    }

    @Override
    public void gc(int orphanGraceMinutes, int keepImagesPerTask, Set<String> protectedContainers) {
        delegate.gc(orphanGraceMinutes, keepImagesPerTask, protectedContainers);
        Set<String> owned;
        try {
            owned = dns.listOwned();
        } catch (Exception e) {
            log.warn("공개 주소 DNS 목록 조회 실패 — 이번 주기 DNS 정리 스킵: {}", e.getMessage());
            return;
        }
        int removed = 0;
        for (String host : owned) {
            OptionalLong id = PublicRoute.taskIdOf(host, baseDomain, slugSuffix);
            if (id.isEmpty()) continue;
            String container = DockerGcPlanner.OWN_PREFIX + id.getAsLong();
            if (protectedContainers.contains(container)) continue;
            if (delegate.status(container) != DeployStatus.STOPPED) continue;
            try {
                dns.delete(host);
                removed++;
            } catch (Exception e) {
                log.warn("GC 공개 주소 DNS 삭제 실패: {} — {}", host, e.getMessage());
            }
        }
        if (removed > 0) log.info("GC: 공개 주소 DNS 레코드 {}개 제거", removed);
    }

    private static OptionalLong taskIdOfContainer(String containerName) {
        if (containerName == null) return OptionalLong.empty();
        Matcher m = OWN_CONTAINER.matcher(containerName);
        return m.matches() ? OptionalLong.of(Long.parseLong(m.group(1))) : OptionalLong.empty();
    }
}
