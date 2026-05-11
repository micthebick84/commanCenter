package com.hamonsoft.netismaker.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 비즈니스 룰 위반 시 던지는 예외. HTTP 매핑은 GlobalExceptionHandler에서.
 */
public class TaskException extends RuntimeException {

    private final HttpStatus status;

    public TaskException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static TaskException notFound() {
        return new TaskException(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다");
    }

    public static TaskException forbidden() {
        return new TaskException(HttpStatus.FORBIDDEN, "권한이 없습니다");
    }

    public static TaskException conflict(String message) {
        return new TaskException(HttpStatus.CONFLICT, message);
    }

    public static TaskException tooManyRequests(String message) {
        return new TaskException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
