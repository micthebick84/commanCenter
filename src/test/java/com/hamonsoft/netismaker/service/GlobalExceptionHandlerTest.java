package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 단위 테스트 — malformed JSON(400) 처리.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void malformed_json_returns_400_with_project_error_shape() {
        HttpMessageNotReadableException e =
                new HttpMessageNotReadableException("broken", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<Map<String, Object>> resp = handler.handleUnreadable(e);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsKey("error");
        assertThat(resp.getBody()).containsKey("message");
        assertThat(resp.getBody().get("error")).isEqualTo("Bad Request");
    }
}
