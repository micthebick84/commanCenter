package com.hamonsoft.netismaker.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestKindTest {

    @Test
    void 라벨은_DEV_개발_요청_REPO_사이트_추가() {
        assertThat(RequestKind.DEV.label()).isEqualTo("개발 요청");
        assertThat(RequestKind.REPO.label()).isEqualTo("사이트 추가");
    }

    @Test
    void creatable은_DEV와_REPO만_true() {
        assertThat(RequestKind.DEV.creatable()).isTrue();
        assertThat(RequestKind.REPO.creatable()).isTrue();
        assertThat(RequestKind.FEATURE.creatable()).isFalse();
        assertThat(RequestKind.MCP.creatable()).isFalse();
    }

    @Test
    void publicToAll은_DEV만_true() {
        assertThat(RequestKind.DEV.publicToAll()).isTrue();
        assertThat(RequestKind.REPO.publicToAll()).isFalse();
        assertThat(RequestKind.FEATURE.publicToAll()).isFalse();
        assertThat(RequestKind.MCP.publicToAll()).isFalse();
    }
}
