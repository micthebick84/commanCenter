package com.hamonsoft.netismaker.service;

import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Row;
import com.hamonsoft.netismaker.dto.DeployEnvSuggestionResponse.Source;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.entity.DeployConfig;
import com.hamonsoft.netismaker.entity.DeployConfig.DbSlot;
import com.hamonsoft.netismaker.entity.DeployConfig.Target;
import com.hamonsoft.netismaker.entity.DeployConfig.TargetKind;
import com.hamonsoft.netismaker.entity.DeployConfig.Variable;
import com.hamonsoft.netismaker.entity.EnvTemplateItem;
import com.hamonsoft.netismaker.entity.EnvVar;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeployEnvSuggesterCatalogTest {

    private static Variable variable(String key, boolean secret, boolean required, String format) {
        return new Variable(key, key + " 설명", secret, required, format, null, null);
    }

    private static DbSlot envSlot(String slot, String... envNames) {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        for (String n : envNames) entries.put(n, "url");
        return new DbSlot(slot, slot, List.of(DbType.POSTGRESQL), new Target(TargetKind.ENV, null, entries));
    }

    @Test
    void cfg가_없으면_기존과_같다() {
        List<EnvVar> prev = List.of(new EnvVar("DB_URL", "jdbc:prev", false), new EnvVar("DB_PASSWORD", "pw", true));
        List<EnvTemplateItem> tpl = List.of(new EnvTemplateItem("DB_URL", "DB 접속 URL", false, true));
        DeployEnvSuggestionResponse old = DeployEnvSuggester.suggest(List.of(), tpl, prev, 41L);
        DeployEnvSuggestionResponse neu = DeployEnvSuggester.suggest(List.of(), tpl, prev, 41L, null, List.of());

        assertThat(neu).isEqualTo(old);
        assertThat(neu.databases()).isEmpty();
    }

    @Test
    void 카탈로그_변수가_먼저_고정으로() {
        DeployConfig cfg = new DeployConfig(List.of(), List.of(variable("JWT_SECRET", true, true, "32자 이상")));
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(new EnvVar("EXTRA", "x", false)), List.of(new EnvTemplateItem("TPL", "t", false, false)),
                List.of(), null, cfg, List.of());

        assertThat(r.rows()).extracting(Row::key).containsExactly("JWT_SECRET", "EXTRA", "TPL");
        Row first = r.rows().get(0);
        assertThat(first.source()).isEqualTo(Source.CATALOG);
        assertThat(first.format()).isEqualTo("32자 이상");
        assertThat(first.secret()).isTrue();
        assertThat(first.required()).isTrue();
    }

    @Test
    void 카탈로그_변수는_저장값을_쓰고_저장_env가_없을때만_직전배포_값을_쓴다() {
        DeployConfig cfg = new DeployConfig(List.of(), List.of(variable("A", false, false, null)));
        DeployEnvSuggestionResponse withPrev = DeployEnvSuggester.suggest(
                List.of(), List.of(), List.of(new EnvVar("A", "prev", false)), 7L, cfg, List.of());
        assertThat(withPrev.rows().get(0).value()).isEqualTo("prev");
        assertThat(withPrev.rows().get(0).valueFromPrevious()).isTrue();
        assertThat(withPrev.previousTaskId()).isEqualTo(7L);

        DeployEnvSuggestionResponse saved = DeployEnvSuggester.suggest(
                List.of(new EnvVar("OTHER", "o", false)), List.of(), List.of(new EnvVar("A", "prev", false)), 7L, cfg,
                List.of());
        assertThat(saved.rows().get(0).value()).isEmpty();
        assertThat(saved.previousTaskId()).isNull();
    }

    @Test
    void 직전배포_자유행은_가져오지_않는다() {
        DeployConfig cfg = new DeployConfig(List.of(), List.of(variable("A", false, false, null)));
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(), List.of(), List.of(new EnvVar("JDBC_URL", "jdbc:x", false)), 49L, cfg, List.of());

        assertThat(r.rows()).extracting(Row::key).containsExactly("A");
        assertThat(r.previousTaskId()).isNull();
    }

    @Test
    void DB_ENV대상과_같은이름_저장행은_숨긴다() {
        DeployConfig cfg = new DeployConfig(List.of(envSlot("main", "JDBC_URL", "JDBC_USER")), List.of());
        DeployEnvSuggestionResponse r = DeployEnvSuggester.suggest(
                List.of(new EnvVar("JDBC_URL", "jdbc:old", false), new EnvVar("KEEP", "k", false)),
                List.of(new EnvTemplateItem("JDBC_USER", "u", false, false), new EnvTemplateItem("TPL", "t", false, false)),
                List.of(), null, cfg, List.of());

        assertThat(r.rows()).extracting(Row::key).containsExactly("KEEP", "TPL");
    }
}
