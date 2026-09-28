package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerPropertiesDeployTest {

    private static WorkerProperties.Deploy deployWith(WorkerProperties.Deploy.PublicAccess pa) {
        return new WorkerProperties.Deploy(
                null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, null, 0, pa);
    }

    @Test
    void publicAccess_defaults_when_null() {
        WorkerProperties.Deploy d = deployWith(null);
        assertThat(d.publicAccess()).isNotNull();
        assertThat(d.publicAccess().enabled()).isFalse();
        assertThat(d.publicAccess().baseDomain()).isEqualTo("micthebick.dev");
        assertThat(d.publicAccess().network()).isEqualTo("netis-deploy");
        assertThat(d.publicAccess().slugSuffix()).isEmpty();
        assertThat(d.publicAccess().dns().provider()).isEqualTo("none");
        assertThat(d.publicAccess().dns().cloudflare()).isFalse();
    }

    @Test
    void reconcile_defaults_enabled_with_5min_interval() {
        WorkerProperties.Deploy d = deployWith(null);
        assertThat(d.reconcileEnabled()).isTrue();
        assertThat(d.reconcileIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void publicAccess_keeps_explicit_values() {
        WorkerProperties.Deploy.PublicAccess pa = new WorkerProperties.Deploy.PublicAccess(
                true, "example.com", "web", "-win",
                new WorkerProperties.Deploy.PublicAccess.Dns("Cloudflare", "tok", "zone", "tun"));
        WorkerProperties.Deploy d = deployWith(pa);
        assertThat(d.publicAccess().enabled()).isTrue();
        assertThat(d.publicAccess().baseDomain()).isEqualTo("example.com");
        assertThat(d.publicAccess().network()).isEqualTo("web");
        assertThat(d.publicAccess().slugSuffix()).isEqualTo("-win");
        assertThat(d.publicAccess().dns().provider()).isEqualTo("cloudflare");   // 소문자 정규화
        assertThat(d.publicAccess().dns().cloudflare()).isTrue();
    }

    @Test
    void slugSuffix_rejects_non_dns_label_values() {
        for (String bad : new String[]{"win", "_win", "-Win", "-win.", "--", "-"}) {
            assertThatThrownBy(() -> new WorkerProperties.Deploy.PublicAccess(null, null, null, bad, null))
                    .as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("slug-suffix");
        }
    }

    @Test
    void slugSuffix_accepts_multi_segment_suffix() {
        assertThat(new WorkerProperties.Deploy.PublicAccess(null, null, null, " -win-2 ", null).slugSuffix())
                .isEqualTo("-win-2");
    }

    @Test
    void dns_toString_never_prints_the_token() {
        WorkerProperties.Deploy.PublicAccess.Dns dns =
                new WorkerProperties.Deploy.PublicAccess.Dns("cloudflare", "cf-secret-123", "zone", "tun");
        assertThat(dns.toString()).doesNotContain("cf-secret-123").contains("zone").contains("tun");
    }

    @Test
    void dns_rejects_a_misspelled_provider() {
        assertThatThrownBy(() -> new WorkerProperties.Deploy.PublicAccess.Dns("cloudfalre", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DEPLOY_PUBLIC_DNS_PROVIDER")
                .hasMessageContaining("cloudfalre");
    }

    @Test
    void dns_normalizes_provider_before_validating() {
        WorkerProperties.Deploy.PublicAccess.Dns dns =
                new WorkerProperties.Deploy.PublicAccess.Dns(" CloudFlare ", "tok", "zone", "tun");
        assertThat(dns.provider()).isEqualTo("cloudflare");
    }

    @Test
    void baseDomain_is_lowercased_after_defaulting() {
        WorkerProperties.Deploy.PublicAccess pa =
                new WorkerProperties.Deploy.PublicAccess(null, "MicTheBick.DEV", null, null, null);
        assertThat(pa.baseDomain()).isEqualTo("micthebick.dev");
    }
}
