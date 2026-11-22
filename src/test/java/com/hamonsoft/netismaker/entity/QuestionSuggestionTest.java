package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuestionSuggestionTest {

    @Test void db_제안은_PENDING으로_만들어지고_대상은_선택이다() {
        QuestionSuggestion s = QuestionSuggestion.db(7L, 4, null, "설정 값이 테이블에 있다", List.of("tb_config"));
        assertThat(s.getKind()).isEqualTo(SuggestionKind.DB);
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.PENDING);
        assertThat(s.getDbConnectionId()).isNull();
        assertThat(s.getTargets()).containsExactly("tb_config");
        assertThat(s.getRepoCatalogId()).isNull();
    }

    @Test void decide는_PENDING에서_한_번만_바뀐다() {
        QuestionSuggestion s = QuestionSuggestion.refRepo(7L, 4, 9L, "호출 대상이 이 레포에 있다");
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(s.decide(SuggestionStatus.ACCEPTED, "user1", now)).isTrue();
        assertThat(s.decide(SuggestionStatus.DISMISSED, "admin1", now.plusMinutes(1))).isFalse();
        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.ACCEPTED);
        assertThat(s.getDecidedBy()).isEqualTo("user1");
        assertThat(s.getDecidedAt()).isEqualTo(now);
    }

    @Test void decide는_20자를_넘는_결정자를_잘라_저장한다() {
        QuestionSuggestion s = QuestionSuggestion.refRepo(7L, 4, 9L, "r");
        assertThat(s.decide(SuggestionStatus.DISMISSED, "admin-with-a-very-long-name-1", OffsetDateTime.now())).isTrue();
        assertThat(s.getDecidedBy()).isEqualTo("admin-with-a-very-lo");

        QuestionSuggestion n = QuestionSuggestion.refRepo(7L, 4, 9L, "r");
        assertThat(n.decide(SuggestionStatus.SUPERSEDED, null, OffsetDateTime.now())).isTrue();
        assertThat(n.getDecidedBy()).isNull();
    }

    @Test void decide에_PENDING을_주면_거절한다() {
        assertThatThrownBy(() -> QuestionSuggestion.db(1L, 1, null, "r", List.of())
                .decide(SuggestionStatus.PENDING, "u", OffsetDateTime.now())).isInstanceOf(IllegalArgumentException.class);
    }
}
