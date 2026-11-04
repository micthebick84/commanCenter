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
    void java_locale_is_appended_at_the_end() {
        String dockerfile = "FROM tomcat:7-jre8\nCOPY app.war /usr/local/tomcat/webapps/ROOT.war\nEXPOSE 8080\n";

        assertThat(DockerfileSupport.appendJavaLocale(dockerfile))
                .hasValue(dockerfile + DockerfileSupport.JAVA_LOCALE_BLOCK);
        assertThat(DockerfileSupport.JAVA_LOCALE_BLOCK)
                .endsWith("ENV JAVA_TOOL_OPTIONS=\"-Duser.language=ko -Duser.country=KR\"\n");
    }

    @Test
    void artifact_copy_is_appended_at_the_end() {
        assertThat(DockerfileSupport.appendArtifactCopy("FROM a AS b\nFROM tomcat\nCMD [\"run\"]\n"))
                .isEqualTo("FROM a AS b\nFROM tomcat\nCMD [\"run\"]\n" + DockerfileSupport.ARTIFACT_COPY_BLOCK);
        assertThat(DockerfileSupport.ARTIFACT_COPY_BLOCK).isEqualTo(
                "# 종료 산출물 납품 파일 (Symphony 워커가 붙임)\nCOPY .netis-prebuild/artifact/ /opt/netis-artifact/\n");
    }

    @Test
    void artifact_copy_starts_on_its_own_line_when_last_line_has_no_newline() {
        assertThat(DockerfileSupport.appendArtifactCopy("FROM x\nCMD [\"run\"]"))
                .isEqualTo("FROM x\nCMD [\"run\"]\n" + DockerfileSupport.ARTIFACT_COPY_BLOCK);
    }

    @Test
    void java_locale_starts_on_its_own_line_when_last_line_has_no_newline() {
        assertThat(DockerfileSupport.appendJavaLocale("FROM x\nCMD [\"run\"]"))
                .hasValue("FROM x\nCMD [\"run\"]\n" + DockerfileSupport.JAVA_LOCALE_BLOCK);
    }

    @Test
    void java_locale_is_left_alone_when_the_dockerfile_already_sets_it() {
        assertThat(DockerfileSupport.appendJavaLocale(
                "FROM x\nENV JAVA_TOOL_OPTIONS=-Xss1m\n")).isEmpty();
        assertThat(DockerfileSupport.appendJavaLocale(
                "FROM x\nENV JAVA_OPTS=\"-Xmx2g -Duser.language=en\"\n")).isEmpty();
    }

    @Test
    void java_locale_ignores_mentions_in_comments() {
        String dockerfile = "FROM x\n# JAVA_TOOL_OPTIONS -Duser.language 는 워커가 정한다\n";

        assertThat(DockerfileSupport.appendJavaLocale(dockerfile))
                .hasValue(dockerfile + DockerfileSupport.JAVA_LOCALE_BLOCK);
    }

    @Test
    void java_locale_of_null_dockerfile() {
        assertThat(DockerfileSupport.appendJavaLocale(null)).isEmpty();
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
