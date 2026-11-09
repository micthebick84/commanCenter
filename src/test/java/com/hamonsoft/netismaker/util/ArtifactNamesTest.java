package com.hamonsoft.netismaker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactNamesTest {

    @Test
    void 확장자는_대소문자_무시로_찾고_소문자로_돌려준다() {
        assertThat(ArtifactNames.extension("a.war")).contains("war");
        assertThat(ArtifactNames.extension("/x/ROOT.WAR")).contains("war");
        assertThat(ArtifactNames.extension("b.jar")).contains("jar");
        assertThat(ArtifactNames.extension("knu-wars.zip")).contains("zip");
        assertThat(ArtifactNames.extension("netis-dash.TAR.GZ")).contains("tar.gz");
        assertThat(ArtifactNames.extension("x.tgz")).contains("tgz");
    }

    @Test
    void 목록_밖_확장자는_비어_있다() {
        for (String n : new String[]{"x.gz", "x.tar", "x.tar.gzx", "x.zipx", "x.ear", "war", "", null}) {
            assertThat(ArtifactNames.extension(n)).as(String.valueOf(n)).isEmpty();
        }
    }
}
