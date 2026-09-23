package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application-worker.yml을 실제로 Spring Environment에 얹어 WorkerProperties로 바인딩되는지 확인.
 * Docker/전체 애플리케이션 컨텍스트 없이도 yml의 키 이름·플레이스홀더가 레코드 필드와 어긋나지 않았는지
 * (예: public-access 아래 dns 블록 경로, slug-suffix 하이픈 표기) 잡아내는 게 목적.
 */
class WorkerPropertiesYamlBindingTest {

    private static StandardEnvironment loadYaml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> loaded =
                loader.load("application-worker.yml", new ClassPathResource("application-worker.yml"));
        for (PropertySource<?> ps : loaded) {
            env.getPropertySources().addLast(ps);
        }
        return env;
    }

    @Test
    void binds_cloudflare_public_access_settings_from_env_style_overrides() throws Exception {
        StandardEnvironment env = loadYaml();
        // MapPropertySource를 addFirst로 얹어 yml의 ${VAR:default} 플레이스홀더보다 먼저 해석되게 한다
        // (운영에서는 이 값들이 실제 OS 환경변수로 들어온다).
        Map<String, Object> overrides = Map.of(
                "DEPLOY_PUBLIC_ACCESS_ENABLED", "true",
                "DEPLOY_PUBLIC_SLUG_SUFFIX", "-win",
                "DEPLOY_PUBLIC_DNS_PROVIDER", "cloudflare",
                "CLOUDFLARE_API_TOKEN", "tok",
                "CLOUDFLARE_ZONE_ID", "zone-1",
                "CLOUDFLARE_TUNNEL_ID", "tun-1"
        );
        env.getPropertySources().addFirst(new MapPropertySource("test-env-overrides", overrides));

        WorkerProperties props = Binder.get(env).bind("netis-maker.worker", WorkerProperties.class).get();

        WorkerProperties.Deploy.PublicAccess pa = props.deploy().publicAccess();
        assertThat(pa.enabled()).isTrue();
        assertThat(pa.slugSuffix()).isEqualTo("-win");
        assertThat(pa.baseDomain()).isEqualTo("micthebick.dev");
        assertThat(pa.dns().provider()).isEqualTo("cloudflare");
        assertThat(pa.dns().zoneId()).isEqualTo("zone-1");
        assertThat(pa.dns().tunnelId()).isEqualTo("tun-1");
        assertThat(pa.dns().apiToken()).isEqualTo("tok");
    }

    @Test
    void binds_defaults_when_no_env_overrides_are_present() throws Exception {
        StandardEnvironment env = loadYaml();

        WorkerProperties props = Binder.get(env).bind("netis-maker.worker", WorkerProperties.class).get();

        WorkerProperties.Deploy.PublicAccess pa = props.deploy().publicAccess();
        assertThat(pa.enabled()).isFalse();
        assertThat(pa.slugSuffix()).isEmpty();
        assertThat(pa.dns().provider()).isEqualTo("none");
    }
}
