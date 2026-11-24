package com.hamonsoft.netismaker.service.deployconfig;

import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployConfig.Target;
import com.hamonsoft.netismaker.entity.DeployConfig.TargetKind;
import com.hamonsoft.netismaker.entity.DeployConfig.Variable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployConfigValidatorTest {

    private static final String PATH = "WEB-INF/classes/config/jdbc_maria.properties";

    private static LinkedHashMap<String, String> map(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static DbSlot fileSlot(String slot, String path, Map<String, String> entries) {
        return new DbSlot(slot, "주 DB", List.of(DbType.MARIADB, DbType.MYSQL),
                new Target(TargetKind.FILE, path, new LinkedHashMap<>(entries)));
    }

    private static DbSlot envSlot(String slot, Map<String, String> entries) {
        return new DbSlot(slot, "주 DB", List.of(DbType.POSTGRESQL),
                new Target(TargetKind.ENV, null, new LinkedHashMap<>(entries)));
    }

    private static DeployConfig dbs(DbSlot... s) {
        return new DeployConfig(List.of(s), List.of());
    }

    private static Variable envVar(String key) {
        return new Variable(key, null, false, false, null, null, null);
    }

    @Test
    void 넷이스67_예시가_통과한다() {
        DeployConfig in = dbs(fileSlot("main", PATH, map(
                "jdbc.url", "jdbc:log4jdbc:mysql://{host}:{port}/{database}?useSSL=false&allowMultiQueries=true",
                "jdbc.username", "{username}",
                "jdbc.password", "{password}")));
        DeployConfig out = DeployConfigValidator.normalize(in);
        assertThat(out).isNotNull();
        assertThat(out.databases()).hasSize(1);
        assertThat(out.databases().get(0).target().entries()).hasSize(3);
        assertThat(out.databases().get(0).target().path()).isEqualTo(PATH);
    }

    @Test
    void 빈_설정은_null() {
        assertThat(DeployConfigValidator.normalize(null)).isNull();
        assertThat(DeployConfigValidator.normalize(new DeployConfig(null, null))).isNull();
    }

    @Test
    void 앞뒤_공백을_제거한다() {
        DeployConfig out = DeployConfigValidator.normalize(new DeployConfig(
                List.of(envSlot(" main ", map(" A_B ", "{host}"))),
                List.of(new Variable(" KEY ", " 설명 ", true, false, " [{값}] ", null, null))));
        assertThat(out.databases().get(0).slot()).isEqualTo("main");
        assertThat(out.databases().get(0).target().entries()).containsKey("A_B");
        assertThat(out.variables().get(0).key()).isEqualTo("KEY");
        assertThat(out.variables().get(0).format()).isEqualTo("[{값}]");
    }

    @Test
    void 경로가_classes_밖이면_거부() {
        assertThatThrownBy(() -> DeployConfigValidator.normalize(
                dbs(fileSlot("main", "WEB-INF/web.xml", map("a", "{host}")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 점점_경로_거부() {
        assertThatThrownBy(() -> DeployConfigValidator.normalize(
                dbs(fileSlot("main", "WEB-INF/classes/../x.properties", map("a", "{host}")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 역슬래시와_properties_아닌_경로_거부() {
        assertThatThrownBy(() -> DeployConfigValidator.normalize(
                dbs(fileSlot("main", "WEB-INF/classes/a\\b.properties", map("a", "{host}")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeployConfigValidator.normalize(
                dbs(fileSlot("main", "WEB-INF/classes/a.xml", map("a", "{host}")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 빈_항목_값은_거부() {
        assertThatThrownBy(() -> DeployConfigValidator.normalize(
                dbs(envSlot("main", map("URL", "  ")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("비어 있습니다");
        assertThatThrownBy(() -> DeployConfigValidator.normalize(
                dbs(fileSlot("main", PATH, map("jdbc.url", "")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("비어 있습니다");
    }

    @Test
    void 모르는_자리표시자_거부() {
        assertThatThrownBy(() -> DeployConfigValidator.normalize(
                dbs(envSlot("main", map("URL", "jdbc://{host}/{schema}")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");
    }

    @Test
    void 허용된_일곱_자리표시자는_통과() {
        assertThat(DeployConfigValidator.normalize(dbs(envSlot("main", map(
                "A", "{host}{port}{database}{username}{password}{username:url}{password:url}"))))).isNotNull();
    }

    @Test
    void 형식에_값이_두번이면_거부() {
        assertThatThrownBy(() -> DeployConfigValidator.normalize(new DeployConfig(List.of(),
                List.of(new Variable("K", null, false, false, "{값}{값}", null, null)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeployConfigValidator.normalize(new DeployConfig(List.of(),
                List.of(new Variable("K", null, false, false, "{값}{x}", null, null)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeployConfigValidator.normalize(new DeployConfig(List.of(),
                List.of(new Variable("K", null, false, false, "없음", null, null)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_env_이름_두번_거부() {
        DeployConfig in = new DeployConfig(
                List.of(envSlot("main", map("SPRING_DATASOURCE_URL", "{host}"))),
                List.of(envVar("SPRING_DATASOURCE_URL")));
        assertThatThrownBy(() -> DeployConfigValidator.normalize(in))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 같은_파일항목_두번_거부() {
        DeployConfig in = new DeployConfig(
                List.of(fileSlot("main", PATH, map("jdbc.url", "{host}"))),
                List.of(new Variable("URL", null, false, false, null, PATH, "jdbc.url")));
        assertThatThrownBy(() -> DeployConfigValidator.normalize(in))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 변수_파일경로와_항목은_둘다_있거나_둘다_없어야() {
        assertThatThrownBy(() -> DeployConfigValidator.normalize(new DeployConfig(List.of(),
                List.of(new Variable("K", null, false, false, null, PATH, null)))))
                .isInstanceOf(IllegalArgumentException.class);
        DeployConfig ok = DeployConfigValidator.normalize(new DeployConfig(List.of(),
                List.of(new Variable("K", null, false, false, null, PATH, "k"))));
        assertThat(ok.variables().get(0).filePath()).isEqualTo(PATH);
    }

    @Test
    void 자리_이름_규칙과_중복_상한() {
        assertThatThrownBy(() -> DeployConfigValidator.normalize(dbs(envSlot("Main", map("A", "x")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeployConfigValidator.normalize(
                dbs(envSlot("main", map("A", "x")), envSlot("main", map("B", "x")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeployConfigValidator.normalize(dbs(
                envSlot("a", map("A", "x")), envSlot("b", map("B", "x")),
                envSlot("c", map("C", "x")), envSlot("d", map("D", "x")))))
                .isInstanceOf(IllegalArgumentException.class);
        List<Variable> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(envVar("V" + i));
        }
        assertThatThrownBy(() -> DeployConfigValidator.normalize(new DeployConfig(List.of(), many)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 깨진_입력은_NPE가_아니라_IllegalArgument() {
        assertThatThrownBy(() -> DeployConfigValidator.normalize(dbs(
                new DbSlot("main", "주", Arrays.asList(DbType.MYSQL, null),
                        new Target(TargetKind.ENV, null, map("A", "x"))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeployConfigValidator.normalize(dbs(
                new DbSlot("main", "주", List.of(DbType.MYSQL), null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeployConfigValidator.normalize(dbs(
                new DbSlot("main", "주", List.of(DbType.MYSQL), new Target(null, null, map("A", "x"))))))
                .isInstanceOf(IllegalArgumentException.class);
        LinkedHashMap<String, String> nullVal = new LinkedHashMap<>();
        nullVal.put("A", null);
        assertThatThrownBy(() -> DeployConfigValidator.normalize(dbs(envSlot("main", nullVal))))
                .isInstanceOf(IllegalArgumentException.class);
        LinkedHashMap<String, String> nullKey = new LinkedHashMap<>();
        nullKey.put(null, "x");
        assertThatThrownBy(() -> DeployConfigValidator.normalize(dbs(envSlot("main", nullKey))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void envNames는_변수와_DB_ENV대상_이름() {
        DeployConfig c = DeployConfigValidator.normalize(new DeployConfig(
                List.of(envSlot("main", map("SPRING_DATASOURCE_URL", "{host}", "SPRING_DATASOURCE_USERNAME", "{username}")),
                        fileSlot("sub", PATH, map("jdbc.url", "{host}"))),
                List.of(envVar("API_KEY"), new Variable("FILEVAR", null, false, false, null, PATH, "x"))));
        assertThat(DeployConfigValidator.envNames(c))
                .containsExactlyInAnyOrder("API_KEY", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME");
        assertThat(DeployConfigValidator.dbEnvNames(c))
                .containsExactlyInAnyOrder("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME");
        assertThat(DeployConfigValidator.envNames(null)).isEmpty();
    }
}
