package com.hamonsoft.netismaker.service.deployconfig;

import com.hamonsoft.netismaker.dto.DeployOverrides;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployConfig.Target;
import com.hamonsoft.netismaker.entity.DeployConfig.TargetKind;
import com.hamonsoft.netismaker.entity.DeployConfig.Variable;
import com.hamonsoft.netismaker.entity.DeployDbChoice;
import com.hamonsoft.netismaker.entity.EnvVar;
import com.hamonsoft.netismaker.service.deployconfig.DeployOverrideAssembler.ResolvedDb;
import com.hamonsoft.netismaker.service.deployconfig.DeployValueTemplates.DbValues;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class DeployOverrideAssemblerTest {

    private static final String URL_TEMPLATE =
            "jdbc:log4jdbc:mysql://{host}:{port}/{database}?useSSL=false&characterEncoding=UTF-8";

    private static LinkedHashMap<String, String> fileEntries() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("jdbc.url", URL_TEMPLATE);
        m.put("jdbc.username", "{username}");
        m.put("jdbc.password", "{password}");
        return m;
    }

    private static DbSlot fileSlot() {
        return new DbSlot("main", "주 DB", List.of(DbType.MYSQL, DbType.MARIADB),
                new Target(TargetKind.FILE, "WEB-INF/classes/jdbc.properties", fileEntries()));
    }

    private static DbSlot envSlot() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("SPRING_DATASOURCE_PASSWORD", "{password}");
        m.put("SPRING_DATASOURCE_USERNAME", "{username}");
        return new DbSlot("main", "주 DB", List.of(DbType.MYSQL), new Target(TargetKind.ENV, null, m));
    }

    private static ResolvedDb db(long id, DbType type, boolean enabled) {
        return new ResolvedDb(id, "개발DB", type, enabled, new DbValues("192.0.2.10", 53306, "netis", "netis", "pw"));
    }

    private static Function<Long, Optional<ResolvedDb>> resolver(ResolvedDb... dbs) {
        Map<Long, ResolvedDb> map = new LinkedHashMap<>();
        for (ResolvedDb d : dbs) {
            map.put(d.id(), d);
        }
        return id -> Optional.ofNullable(map.get(id));
    }

    private static List<EnvVar> saved() {
        return List.of(new EnvVar("A", "1", false), new EnvVar("", "x", false), new EnvVar("B", "", false));
    }

    @Test
    void 파일대상_DB를_고르면_files에_세항목() {
        DeployConfig cfg = new DeployConfig(List.of(fileSlot()), List.of());
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved(),
                List.of(new DeployDbChoice("main", 43L)), resolver(db(43, DbType.MYSQL, true)));

        assertThat(r.error()).isNull();
        assertThat(r.env()).containsExactly(new EnvVar("A", "1", false));
        assertThat(r.files()).hasSize(1);
        assertThat(r.files().get(0).path()).isEqualTo("WEB-INF/classes/jdbc.properties");
        assertThat(r.files().get(0).entries())
                .containsEntry("jdbc.url", "jdbc:log4jdbc:mysql://192.0.2.10:53306/netis?useSSL=false&characterEncoding=UTF-8")
                .containsEntry("jdbc.username", "netis")
                .containsEntry("jdbc.password", "pw");
        assertThat(r.summary())
                .isEqualTo("주 DB = 개발DB (MYSQL 192.0.2.10:53306/netis) → WEB-INF/classes/jdbc.properties 3항목");
    }

    @Test
    void ENV대상_DB가_저장된_같은이름_행을_이긴다() {
        DeployConfig cfg = new DeployConfig(List.of(envSlot()), List.of());
        List<EnvVar> saved = List.of(new EnvVar("X", "1", false),
                new EnvVar("SPRING_DATASOURCE_PASSWORD", "old", false), new EnvVar("Y", "2", false));
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved,
                List.of(new DeployDbChoice("main", 43L)), resolver(db(43, DbType.MYSQL, true)));

        assertThat(r.env()).extracting(EnvVar::value).doesNotContain("old");
        assertThat(r.env()).extracting(EnvVar::key)
                .containsExactly("X", "SPRING_DATASOURCE_PASSWORD", "Y", "SPRING_DATASOURCE_USERNAME");
        assertThat(r.env().get(1)).isEqualTo(new EnvVar("SPRING_DATASOURCE_PASSWORD", "pw", true));
        assertThat(r.env().get(3).secret()).isFalse();
        assertThat(r.summary()).contains("→ env 2항목");
    }

    @Test
    void 고르지_않음이면_적용하지_않는다() {
        DeployConfig cfg = new DeployConfig(List.of(fileSlot()), List.of());
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved(),
                List.of(new DeployDbChoice("main", null)), resolver());

        assertThat(r.error()).isNull();
        assertThat(r.files()).isEmpty();
        assertThat(r.summary()).isNull();
        assertThat(r.env()).hasSize(1);
    }

    @Test
    void 설정에_없는_자리를_고른_채면_error() {
        DeployConfig cfg = new DeployConfig(List.of(fileSlot()), List.of());
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved(),
                List.of(new DeployDbChoice("old", 43L)), resolver(db(43, DbType.MYSQL, true)));

        assertThat(r.error()).isEqualTo("배포 DB 자리 'old'이 카탈로그 배포 설정에 없습니다 — 배포 창에서 다시 고르세요");
        assertThat(r.env()).isEmpty();
        assertThat(r.files()).isEmpty();
    }

    @Test
    void 연결이_없으면_error() {
        DeployConfig cfg = new DeployConfig(List.of(fileSlot()), List.of());
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved(),
                List.of(new DeployDbChoice("main", 43L)), resolver());

        assertThat(r.error()).isEqualTo("배포 DB '주 DB': 연결 #43를 찾을 수 없습니다(삭제·복호화 불가)");
        assertThat(r.env()).isEmpty();
        assertThat(r.files()).isEmpty();
    }

    @Test
    void 비활성이면_error() {
        DeployConfig cfg = new DeployConfig(List.of(fileSlot()), List.of());
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved(),
                List.of(new DeployDbChoice("main", 43L)), resolver(db(43, DbType.MYSQL, false)));

        assertThat(r.error()).isEqualTo("배포 DB '주 DB': 연결 '개발DB'이 비활성입니다");
    }

    @Test
    void 종류가_다르면_error() {
        DeployConfig cfg = new DeployConfig(List.of(new DbSlot("main", "주 DB", List.of(DbType.MARIADB),
                new Target(TargetKind.FILE, "a.properties", fileEntries()))), List.of());
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved(),
                List.of(new DeployDbChoice("main", 43L)), resolver(db(43, DbType.ORACLE, true)));

        assertThat(r.error()).isEqualTo("배포 DB '주 DB': 연결 '개발DB'(ORACLE)은 이 자리의 DB 종류가 아닙니다");
    }

    @Test
    void 변수는_짧은값을_감싼다() {
        Variable v = new Variable("ROOT_URL", "루트", false, false, "https://{값}/app", null, null);
        DeployConfig cfg = new DeployConfig(List.of(), List.of(v));
        List<EnvVar> saved = List.of(new EnvVar("ROOT_URL", "host.local", true), new EnvVar("Z", "9", false));
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved, List.of(), resolver());

        assertThat(r.env()).containsExactly(new EnvVar("ROOT_URL", "https://host.local/app", true),
                new EnvVar("Z", "9", false));
        assertThat(r.files()).isEmpty();
        assertThat(r.summary()).isNull();
    }

    @Test
    void 파일대상_변수는_files로() {
        Variable v = new Variable("LICENSE", "라이선스", true, false, "[{값}]", "conf/app.properties", "license.key");
        DeployConfig cfg = new DeployConfig(List.of(), List.of(v));
        List<EnvVar> saved = List.of(new EnvVar("LICENSE", "abc", true), new EnvVar("Z", "9", false));
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved, List.of(), resolver());

        assertThat(r.env()).containsExactly(new EnvVar("Z", "9", false));
        assertThat(r.files()).hasSize(1);
        assertThat(r.files().get(0).path()).isEqualTo("conf/app.properties");
        assertThat(r.files().get(0).entries()).containsEntry("license.key", "[abc]");
    }

    @Test
    void toString에_비밀번호가_없다() {
        DeployConfig cfg = new DeployConfig(List.of(fileSlot()), List.of());
        DeployOverrides r = DeployOverrideAssembler.assemble(cfg, saved(),
                List.of(new DeployDbChoice("main", 43L)), resolver(db(43, DbType.MYSQL, true)));

        assertThat(r.toString()).doesNotContain("pw");
        assertThat(r.files().get(0).toString()).doesNotContain("pw");
        assertThat(db(43, DbType.MYSQL, true).toString()).doesNotContain("pw");
    }
}
