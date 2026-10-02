package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** provider 값에 따라 DeployTarget 주입처가 받는 빈이 바뀌는지 — 맥 스택(none) 회귀 가드 포함. */
class PublicDnsConfigurationTest {

    private static WorkerProperties props(WorkerProperties.Deploy.PublicAccess.Dns dns) {
        var pa = new WorkerProperties.Deploy.PublicAccess(true, "micthebick.dev", null, "-win", dns);
        var deploy = new WorkerProperties.Deploy(null, null, 0, null, null, null, 0, 0, null,
                null, 0, 0, 0, null, 0, pa);
        return new WorkerProperties("win-worker-1", null, null, null, 0, 0, 0, 0, 0, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, deploy, null, null);
    }

    private ApplicationContextRunner runner(WorkerProperties props) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=worker")
                .withBean(WorkerProperties.class, () -> props)
                .withBean(LocalDockerTarget.class, () -> new LocalDockerTarget(props))
                .withUserConfiguration(PublicDnsConfiguration.class);
    }

    @Test
    void provider_none_keeps_local_docker_target() {
        runner(props(new WorkerProperties.Deploy.PublicAccess.Dns("none", null, null, null)))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(DeployTarget.class)).isInstanceOf(LocalDockerTarget.class);
                });
    }

    @Test
    void provider_cloudflare_wraps_target_with_dns_decorator() {
        runner(props(new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", "tok", "zone-1", "tun-1")))
                .withPropertyValues("netis-maker.worker.deploy.public-access.dns.provider=cloudflare")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(DeployTarget.class)).isInstanceOf(DnsRegisteringDeployTarget.class);
                });
    }

    @Test
    void provider_cloudflare_with_missing_settings_fails_startup() {
        runner(props(new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", null, null, null)))
                .withPropertyValues("netis-maker.worker.deploy.public-access.dns.provider=cloudflare")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("CLOUDFLARE_API_TOKEN");
                });
    }
}
