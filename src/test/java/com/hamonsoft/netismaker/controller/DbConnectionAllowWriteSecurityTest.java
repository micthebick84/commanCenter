package com.hamonsoft.netismaker.controller;

import com.hamonsoft.netismaker.dto.DbConnectionDto;
import com.hamonsoft.netismaker.entity.DbConnection;
import com.hamonsoft.netismaker.entity.DbConnectionScope;
import com.hamonsoft.netismaker.entity.DbType;
import com.hamonsoft.netismaker.service.DbConnectionService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 연결 쓰기 허용 표시의 API 표면 (스펙 2026-11-03 §6.1 조건 4). 리플렉션으로 관리자 전용 표시를 고정한다 —
 * 이 PC에는 Docker가 없어 JWT 왕복 시험은 CI의 DbConnectionApiIntegrationTest 쪽에서 본다.
 */
class DbConnectionAllowWriteSecurityTest {

    private static Method allowWriteMethod() throws NoSuchMethodException {
        return DbConnectionController.class.getMethod("allowWrite", Long.class,
                DbConnectionDto.AllowWriteRequest.class, JwtAuthenticationToken.class);
    }

    @Test
    void allow_write는_관리자_전용() throws Exception {
        Method m = allowWriteMethod();
        PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
        assertThat(pre).as("@PreAuthorize가 있어야 한다").isNotNull();
        assertThat(pre.value()).isEqualTo("hasAuthority('ROLE_ADMIN')");
        assertThat(m.getAnnotation(PutMapping.class).value()).containsExactly("/{id}/allow-write");
    }

    @Test
    void 쓰기허용을_바꾸는_경로는_이_하나뿐이다() {
        // 일반 PUT·POST(등록·수정)는 JWT 인증만 받는다 — 거기서 allow_write가 바뀌면 USER가 켤 수 있다
        List<String> writers = Arrays.stream(DbConnectionController.class.getDeclaredMethods())
                .filter(m -> m.getName().toLowerCase().contains("allowwrite")).map(Method::getName).toList();
        assertThat(writers).containsExactly("allowWrite");
        assertThat(Arrays.stream(DbConnectionDto.UpsertRequest.class.getRecordComponents()).map(rc -> rc.getName()))
                .doesNotContain("allowWrite");
    }

    @Test
    void 바디의_allowWrite는_비울_수_없다() {
        Validator v = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(v.validate(new DbConnectionDto.AllowWriteRequest(null))).isNotEmpty();
        assertThat(v.validate(new DbConnectionDto.AllowWriteRequest(true))).isEmpty();
        assertThat(v.validate(new DbConnectionDto.AllowWriteRequest(false))).isEmpty();
    }

    @Test
    void 컨트롤러는_서비스에_위임하고_View를_돌려준다() throws Exception {
        DbConnectionService service = mock(DbConnectionService.class);
        DbConnection c = DbConnection.create(DbConnectionScope.REPO, 1L, null, "개발DB", DbType.POSTGRESQL,
                "db.local", 5432, "app", "writer", "CIPHER", "admin1");
        ReflectionTestUtils.setField(c, "id", 7L);
        c.setAllowWrite(true);
        when(service.setAllowWrite(7L, true)).thenReturn(c);
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                Jwt.withTokenValue("t").header("alg", "none").claim("username", "admin1").build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        DbConnectionDto.View view = new DbConnectionController(service)
                .allowWrite(7L, new DbConnectionDto.AllowWriteRequest(true), auth);

        verify(service).setAllowWrite(7L, true);
        assertThat(view.allowWrite()).isTrue();
        assertThat(view.id()).isEqualTo(7L);
    }
}
