package com.hamonsoft.netismaker.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AuthContextEmailTest {

    private static JwtAuthenticationToken token(Consumer<Jwt.Builder> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").claim("username", "user1");
        claims.accept(b);
        return new JwtAuthenticationToken(b.build());
    }

    @Test
    void email_클레임을_다듬어_돌려준다() {
        assertThat(AuthContext.email(token(b -> b.claim("email", "  a@hamonsoft.co.kr ")))).isEqualTo("a@hamonsoft.co.kr");
    }

    @Test
    void 없거나_비었거나_너무_길면_null() {
        assertThat(AuthContext.email(token(b -> {}))).isNull();
        assertThat(AuthContext.email(token(b -> b.claim("email", " ")))).isNull();
        assertThat(AuthContext.email(token(b -> b.claim("email", "a".repeat(321))))).isNull();
    }
}
