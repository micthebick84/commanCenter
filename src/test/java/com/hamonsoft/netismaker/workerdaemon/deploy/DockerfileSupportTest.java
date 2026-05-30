package com.hamonsoft.netismaker.workerdaemon.deploy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerfileSupportTest {

    @Test
    void parses_simple_expose() {
        assertThat(DockerfileSupport.parseExposedPort("FROM x\nEXPOSE 8080\n")).hasValue(8080);
    }

    @Test
    void parses_expose_with_protocol() {
        assertThat(DockerfileSupport.parseExposedPort("EXPOSE 3000/tcp")).hasValue(3000);
    }

    @Test
    void takes_first_when_multiple() {
        assertThat(DockerfileSupport.parseExposedPort("EXPOSE 8080\nEXPOSE 9090")).hasValue(8080);
    }

    @Test
    void empty_when_no_expose() {
        assertThat(DockerfileSupport.parseExposedPort("FROM x\nRUN echo hi")).isEmpty();
    }

    @Test
    void ignores_commented_expose() {
        assertThat(DockerfileSupport.parseExposedPort("# EXPOSE 8080\nEXPOSE 5000")).hasValue(5000);
    }
}
