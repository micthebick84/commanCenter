package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloseLinksTest {

    @Test
    void 기준_주소의_끝_슬래시를_정리해_작업_화면_주소를_만든다() {
        assertThat(CloseLinks.taskUrl("https://app-win.micthebick.dev/", 7L)).isEqualTo("https://app-win.micthebick.dev/tasks/7");
        assertThat(CloseLinks.taskUrl(" https://a.test ", 7L)).isEqualTo("https://a.test/tasks/7");
    }

    @Test
    void 기준_주소가_비면_null() {
        assertThat(CloseLinks.taskUrl("", 7L)).isNull();
        assertThat(CloseLinks.taskUrl(null, 7L)).isNull();
    }
}
