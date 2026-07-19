package com.hamonsoft.netismaker.workerdaemon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPropertiesDeployTest {

    @Test
    void publicAccess_defaults_when_null() {
        WorkerProperties.Deploy d = new WorkerProperties.Deploy(
                null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, null, 0, null);
        assertThat(d.publicAccess()).isNotNull();
        assertThat(d.publicAccess().enabled()).isFalse();
        assertThat(d.publicAccess().baseDomain()).isEqualTo("micthebick.dev");
        assertThat(d.publicAccess().network()).isEqualTo("netis-deploy");
    }

    @Test
    void reconcile_defaults_enabled_with_5min_interval() {
        WorkerProperties.Deploy d = new WorkerProperties.Deploy(
                null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, null, 0, null);
        assertThat(d.reconcileEnabled()).isTrue();
        assertThat(d.reconcileIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void publicAccess_keeps_explicit_values() {
        WorkerProperties.Deploy.PublicAccess pa =
                new WorkerProperties.Deploy.PublicAccess(true, "example.com", "web");
        WorkerProperties.Deploy d = new WorkerProperties.Deploy(
                null, null, 0, null, null, null, 0, 0, null, null, 0, 0, 0, null, 0, pa);
        assertThat(d.publicAccess().enabled()).isTrue();
        assertThat(d.publicAccess().baseDomain()).isEqualTo("example.com");
        assertThat(d.publicAccess().network()).isEqualTo("web");
    }
}
