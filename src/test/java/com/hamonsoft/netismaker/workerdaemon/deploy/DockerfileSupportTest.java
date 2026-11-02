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

    @Test
    void base_images_of_multistage_build() {
        String dockerfile = """
                FROM maven:3.6.3-jdk-8 AS build
                RUN mvn package
                FROM tomcat:8.5-jre8-temurin-jammy
                COPY --from=build /out /usr/local/tomcat/webapps
                """;

        assertThat(DockerfileSupport.parseBaseImages(dockerfile))
                .containsExactly("maven:3.6.3-jdk-8", "tomcat:8.5-jre8-temurin-jammy");
    }

    @Test
    void base_images_skip_earlier_stage_names_case_insensitively() {
        String dockerfile = "FROM node:20 AS Deps\nFROM deps AS build\nfrom nginx:1.27\n";

        assertThat(DockerfileSupport.parseBaseImages(dockerfile)).containsExactly("node:20", "nginx:1.27");
    }

    @Test
    void base_images_skip_platform_flag_scratch_variables_and_comments() {
        String dockerfile = """
                ARG BASE=tomcat:9
                # FROM commented:1
                FROM --platform=linux/amd64 eclipse-temurin:21-jre
                FROM ${BASE}
                FROM scratch
                """;

        assertThat(DockerfileSupport.parseBaseImages(dockerfile)).containsExactly("eclipse-temurin:21-jre");
    }

    @Test
    void base_images_are_listed_once() {
        assertThat(DockerfileSupport.parseBaseImages("FROM alpine:3.20 AS a\nFROM alpine:3.20\n"))
                .containsExactly("alpine:3.20");
    }

    @Test
    void base_images_of_null_or_empty_dockerfile() {
        assertThat(DockerfileSupport.parseBaseImages(null)).isEmpty();
        assertThat(DockerfileSupport.parseBaseImages("RUN echo hi\n")).isEmpty();
    }
}
