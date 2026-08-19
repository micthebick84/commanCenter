package com.hamonsoft.netismaker.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 단위 테스트 — malformed JSON(400), multipart 한도 초과(413),
 * 필수 파트 누락(400) 처리.
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

    @Test
    void multipart_한도_초과는_413_한국어_메시지다() {
        var res = handler.handleMaxUpload(new MaxUploadSizeExceededException(25L * 1024 * 1024));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(res.getBody()).containsKeys("error", "message");
        assertThat(res.getBody().get("message").toString()).contains("한도");
    }

    @Test
    void 필수_파트_누락은_400이고_파트명을_알려준다() {
        var res = handler.handleMissingPart(new MissingServletRequestPartException("meta"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("message").toString()).contains("meta");
    }
}
