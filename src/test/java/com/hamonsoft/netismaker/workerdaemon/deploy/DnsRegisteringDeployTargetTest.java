package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DnsRegisteringDeployTargetTest {

    static class FakeTarget implements DeployTarget {
        final List<String> calls = new ArrayList<>();
        final Map<String, DeployStatus> statuses = new HashMap<>();
        boolean failDeploy;

        @Override
        public DeployResult deploy(DeploySpec spec, Consumer<String> logSink) throws Exception {
            calls.add("deploy " + spec.containerName());
            if (failDeploy) throw new DeployFailedException("docker build 실패 (exit=1)", "build log");
            return new DeployResult("https://task-7-win.micthebick.dev", "cid", 19000, spec.imageName(), "delegate log");
        }

        @Override
        public void stop(String containerName) {
            calls.add("stop " + containerName);
        }

        @Override
        public DeployStatus status(String containerName) {
            return statuses.getOrDefault(containerName, DeployStatus.STOPPED);
        }

        @Override
        public void gc(int orphanGraceMinutes, int keepImagesPerTask, Set<String> protectedContainers) {
            calls.add("gc");
        }
    }

    static class FakeDns implements PublicDnsRegistrar {
        final List<String> calls = new ArrayList<>();
        final Set<String> owned = new LinkedHashSet<>();
        Exception upsertError;
        Exception deleteError;
        Exception listError;

        @Override
        public void upsert(String hostname) throws Exception {
            calls.add("upsert " + hostname);
            if (upsertError != null) throw upsertError;
        }

        @Override
        public void delete(String hostname) throws Exception {
            calls.add("delete " + hostname);
            if (deleteError != null) throw deleteError;
        }

        @Override
        public Set<String> listOwned() throws Exception {
            if (listError != null) throw listError;
            return owned;
        }
    }

    private FakeTarget delegate;
    private FakeDns dns;
    private DnsRegisteringDeployTarget target;
    private final List<String> sink = new ArrayList<>();

    @BeforeEach
    void setUp() {
        delegate = new FakeTarget();
        dns = new FakeDns();
        target = new DnsRegisteringDeployTarget(delegate, dns, "micthebick.dev", "-win");
    }

    private static DeployTarget.DeploySpec spec() {
        return new DeployTarget.DeploySpec(7, Path.of("wt"), "netis-task-7:abc1234", "netis-task-7", 8080,
                Map.of(), Map.of("netis-maker.task", "7"));
    }

    @Test
    void deploy_registers_dns_after_container_is_up() throws Exception {
        DeployTarget.DeployResult r = target.deploy(spec(), sink::add);

        assertThat(delegate.calls).containsExactly("deploy netis-task-7");
        assertThat(dns.calls).containsExactly("upsert task-7-win.micthebick.dev");
        assertThat(r.url()).isEqualTo("https://task-7-win.micthebick.dev");
        assertThat(r.log()).contains("delegate log").contains("[공개 주소 DNS 등록: task-7-win.micthebick.dev]");
        assertThat(sink).contains("[공개 주소 DNS 등록: task-7-win.micthebick.dev]");
    }

    @Test
    void deploy_dns_failure_removes_container_and_fails_deploy() {
        dns.upsertError = new IOException("Cloudflare API POST 실패 (HTTP 403): 10000 Authentication error");

        assertThatThrownBy(() -> target.deploy(spec(), sink::add))
                .isInstanceOf(DeployFailedException.class)
                .hasMessageContaining("공개 주소 DNS 등록 실패")
                .hasMessageContaining("Authentication error")
                .satisfies(e -> assertThat(((DeployFailedException) e).getLog())
                        .contains("delegate log").contains("DNS 등록 실패"));
        assertThat(delegate.calls).containsExactly("deploy netis-task-7", "stop netis-task-7");
    }

    @Test
    void deploy_container_failure_skips_dns() {
        delegate.failDeploy = true;

        assertThatThrownBy(() -> target.deploy(spec(), sink::add))
                .isInstanceOf(DeployFailedException.class)
                .hasMessageContaining("docker build 실패");
        assertThat(dns.calls).isEmpty();
    }

    @Test
    void deploy_accepts_null_log_sink() throws Exception {
        assertThat(target.deploy(spec(), null).url()).isEqualTo("https://task-7-win.micthebick.dev");
    }

    @Test
    void stop_removes_container_then_dns_record() throws Exception {
        target.stop("netis-task-7");

        assertThat(delegate.calls).containsExactly("stop netis-task-7");
        assertThat(dns.calls).containsExactly("delete task-7-win.micthebick.dev");
    }

    @Test
    void stop_swallows_dns_delete_failure() throws Exception {
        dns.deleteError = new IOException("Cloudflare API DELETE 실패 (HTTP 500): (오류 메시지 없음)");

        target.stop("netis-task-7");   // 예외 없음 — 남은 레코드는 GC가 회수

        assertThat(delegate.calls).containsExactly("stop netis-task-7");
    }

    @Test
    void stop_skips_dns_for_foreign_container_names() throws Exception {
        target.stop("traefik");

        assertThat(dns.calls).isEmpty();
    }

    @Test
    void status_is_delegated() {
        delegate.statuses.put("netis-task-7", DeployTarget.DeployStatus.RUNNING);

        assertThat(target.status("netis-task-7")).isEqualTo(DeployTarget.DeployStatus.RUNNING);
    }

    @Test
    void gc_deletes_only_unprotected_records_whose_container_is_stopped() {
        dns.owned.addAll(List.of(
                "task-1-win.micthebick.dev",   // 보호(배포완료)
                "task-2-win.micthebick.dev",   // 비보호 + 컨테이너 없음 → 삭제
                "task-3-win.micthebick.dev",   // 비보호지만 RUNNING — 배포 직후 보고 전(경합) → 보존
                "task-5-win.micthebick.dev",   // 비보호지만 UNKNOWN(데몬 불통) → 보존(fail-closed)
                "task-4.micthebick.dev",       // 접미사 불일치(맥 스택 이름) → 무시
                "manual.micthebick.dev"));     // 형식 불일치 → 무시
        delegate.statuses.put("netis-task-3", DeployTarget.DeployStatus.RUNNING);
        delegate.statuses.put("netis-task-5", DeployTarget.DeployStatus.UNKNOWN);

        target.gc(60, 1, Set.of("netis-task-1"));

        assertThat(delegate.calls).containsExactly("gc");
        assertThat(dns.calls).containsExactly("delete task-2-win.micthebick.dev");
    }

    @Test
    void gc_skips_dns_sweep_when_listing_fails() {
        dns.listError = new IOException("Cloudflare API GET 실패 (HTTP 500): (오류 메시지 없음)");

        target.gc(60, 1, Set.of());

        assertThat(delegate.calls).containsExactly("gc");
        assertThat(dns.calls).isEmpty();
    }
}
