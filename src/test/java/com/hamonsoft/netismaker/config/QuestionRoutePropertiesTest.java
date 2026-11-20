package com.hamonsoft.netismaker.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionRoutePropertiesTest {

    private static QuestionRouteProperties props(String key) {
        return new QuestionRouteProperties(true, key, "jev-1.14.0", "http://x", 3000, 500,
                new QuestionRouteProperties.Threshold(0.7, 0.7, 0.3, 0.5, 0.6));
    }

    @Test
    void toString에는_API_키가_나오지_않는다() {
        String s = props("tsk-secret-value").toString();

        assertThat(s).doesNotContain("tsk-secret-value");
        assertThat(s).contains("apiKey=***");
    }

    @Test
    void 키가_비어_있으면_빈_값으로_보인다() {
        assertThat(props("").toString()).contains("apiKey=,");
    }
}
