package com.hamonsoft.netismaker.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DeployConfig·DeployDbChoice가 JSONB 칸에 쓰는 Jackson 왕복 (Testcontainers 없이 이 PC에서 돈다). */
class DeployConfigJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private static DeployConfig sample() {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        entries.put("jdbc.url", "url");
        entries.put("jdbc.username", "user");
        entries.put("jdbc.password", "pw");
        return new DeployConfig(
                List.of(new DeployConfig.DbSlot("main", "메인 DB", List.of(DbType.POSTGRESQL, DbType.MARIADB),
                        new DeployConfig.Target(DeployConfig.TargetKind.FILE, "WEB-INF/db.properties", entries))),
                List.of(new DeployConfig.Variable("API_KEY", "키", true, true, null, null, null)));
    }

    @Test
    void 엔트리_순서가_유지된다() throws Exception {
        DeployConfig back = mapper.readValue(mapper.writeValueAsString(sample()), DeployConfig.class);

        assertThat(back).isEqualTo(sample());
        assertThat(back.databases().get(0).target().entries().keySet())
                .containsExactly("jdbc.url", "jdbc.username", "jdbc.password");
    }

    @Test
    void null_목록은_빈_목록이_된다() throws Exception {
        DeployConfig built = new DeployConfig(null, null);
        DeployConfig back = mapper.readValue("{}", DeployConfig.class);

        assertThat(built.databases()).isEmpty();
        assertThat(built.variables()).isEmpty();
        assertThat(back.databases()).isEmpty();
        assertThat(back.variables()).isEmpty();
    }

    @Test
    void 빈_설정은_isEmpty다() {
        assertThat(new DeployConfig(null, null).isEmpty()).isTrue();
        assertThat(sample().isEmpty()).isFalse();
        assertThat(new DeployConfig(List.of(), sample().variables()).isEmpty()).isFalse();
    }

    @Test
    void 연결_고르기_null은_왕복해도_null이다() throws Exception {
        List<DeployDbChoice> choices = new ArrayList<>(List.of(
                new DeployDbChoice("main", 43L), new DeployDbChoice("extra", null)));

        List<DeployDbChoice> back = mapper.readValue(mapper.writeValueAsString(choices),
                new TypeReference<List<DeployDbChoice>>() {});

        assertThat(back).isEqualTo(choices);
        assertThat(back.get(1).connectionId()).isNull();
    }
}
