package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.ApproveRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 승인 바디 검증 (스펙 2026-10-24 §4.2) — 파라미터에 @Valid가 있어야 refRepos 원소의 브랜치 255자 상한이 돈다.
 * 바디는 지금처럼 생략할 수 있어야 한다(required = false).
 */
class TaskControllerApproveValidTest {

    @Test
    void approve_body_is_validated() throws Exception {
        Method m = TaskController.class.getMethod("approve", Long.class, ApproveRequest.class, JwtAuthenticationToken.class);
        assertThat(m.getParameters()[1].isAnnotationPresent(jakarta.validation.Valid.class)).isTrue();
    }

    @Test
    void approve_body_stays_optional() throws Exception {
        Method m = TaskController.class.getMethod("approve", Long.class, ApproveRequest.class, JwtAuthenticationToken.class);
        Parameter body = m.getParameters()[1];
        assertThat(body.isAnnotationPresent(RequestBody.class)).isTrue();
        assertThat(body.getAnnotation(RequestBody.class).required()).isFalse();
    }
}
