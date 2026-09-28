package com.hamonsoft.netismaker.workerdaemon.deploy;

import com.hamonsoft.netismaker.workerdaemon.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 배포별 DNS 자동 등록(provider=cloudflare)일 때만 LocalDockerTarget을 DNS 데코레이터로 감싸
 * DeployTarget 주입처(DeployService·DockerGcJob·DeployReconcileJob)에 @Primary로 공급한다.
 * provider=none(기본, 맥 스택)이면 이 빈이 없어 기존 LocalDockerTarget이 그대로 주입된다.
 * 설정 누락은 fromConfig가 예외 → 워커 기동 실패.
 */
@Configuration
@Profile("worker")
public class PublicDnsConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "netis-maker.worker.deploy.public-access.dns", name = "provider",
            havingValue = "cloudflare")
    DeployTarget dnsRegisteringDeployTarget(LocalDockerTarget local, WorkerProperties props) {
        WorkerProperties.Deploy.PublicAccess pa = props.deploy().publicAccess();
        return new DnsRegisteringDeployTarget(local, CloudflareDnsRegistrar.fromConfig(pa, props.id()),
                pa.baseDomain(), pa.slugSuffix());
    }
}
