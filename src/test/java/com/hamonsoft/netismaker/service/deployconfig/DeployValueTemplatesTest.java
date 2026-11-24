package com.hamonsoft.netismaker.service.deployconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.hamonsoft.netismaker.service.deployconfig.DeployValueTemplates.DbValues;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeployValueTemplatesTest {

    @Test
    void jdbc_url을_채운다() {
        String r = DeployValueTemplates.fillDb(
                "jdbc:log4jdbc:mysql://{host}:{port}/{database}?useSSL=false",
                new DbValues("192.0.2.10", 53306, "netis", "netis", "p"));
        assertThat(r).isEqualTo("jdbc:log4jdbc:mysql://192.0.2.10:53306/netis?useSSL=false");
    }

    @Test
    void url_자리표시자는_퍼센트_인코딩한다() {
        String r = DeployValueTemplates.fillDb(
                "postgresql://{username:url}:{password:url}@{host}:{port}/{database}",
                new DbValues("192.0.2.30", 55432, "netis", "ne tis", "p@ss:w/rd#%한"));
        assertThat(r).isEqualTo("postgresql://ne%20tis:p%40ss%3Aw%2Frd%23%25%ED%95%9C@192.0.2.30:55432/netis");
    }

    @Test
    void 일반_자리표시자는_인코딩하지_않는다() {
        String r = DeployValueTemplates.fillDb("{password}",
                new DbValues("h", 1, "d", "u", "p@ss:w/rd"));
        assertThat(r).isEqualTo("p@ss:w/rd");
    }

    @Test
    void placeholders는_중괄호_토큰을_돌려준다() {
        assertThat(DeployValueTemplates.placeholders("{host}:{port}/{x}"))
                .isEqualTo(Set.of("host", "port", "x"));
    }

    @Test
    void 짧은_값을_감싼다() {
        assertThat(DeployValueTemplates.wrapVariable("https://{값}.micthebick.dev", "task-1"))
                .isEqualTo("https://task-1.micthebick.dev");
    }

    @Test
    void 이미_전체_값이면_그대로() {
        String full = "https://task-1.micthebick.dev";
        assertThat(DeployValueTemplates.wrapVariable("https://{값}.micthebick.dev", full)).isEqualTo(full);
    }

    @Test
    void 형식이_없으면_그대로() {
        assertThat(DeployValueTemplates.wrapVariable(null, "abc")).isEqualTo("abc");
    }

    @Test
    void DbValues_toString에_비밀번호가_없다() {
        String s = new DbValues("h", 1, "d", "u", "secretpw").toString();
        assertThat(s).doesNotContain("secretpw").contains("***");
    }
}
