package com.hamonsoft.netismaker.service;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Profile("api")
public class GlobalExceptionHandler {

    @ExceptionHandler(TaskException.class)
    public ResponseEntity<Map<String, Object>> handleTask(TaskException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.getStatus().getReasonPhrase());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    /** 요청 본문이 깨진 JSON 등으로 역직렬화 불가 시 — 프로젝트 공통 에러 형식(400)으로 응답. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Bad Request");
        body.put("message", "요청 본문을 읽을 수 없습니다 (JSON 형식 오류)");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fields = new HashMap<>();
        e.getBindingResult().getFieldErrors().forEach(
                err -> fields.put(err.getField(), err.getDefaultMessage()));
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Bad Request");
        body.put("message", "검증 오류");
        body.put("fields", fields);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 스프링 multipart 한도(spring.servlet.multipart.*, 25MB/60MB) 초과 — 앱 검증(20/50MB)에
     * 도달하기 전에 여기 걸린다. 기본 에러로 새지 않게 프로젝트 공통 형식으로 변환 (스펙 §6.1).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Payload Too Large");
        body.put("message", "업로드 용량 한도를 초과했습니다 (파일당 최대 25MB, 요청 전체 60MB)");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "Bad Request");
        body.put("message", "필수 요청 파트가 없습니다: " + e.getRequestPartName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
