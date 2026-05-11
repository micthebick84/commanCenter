package com.hamonsoft.netismaker.controller;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * JWT 토큰에서 user_id/role을 일관되게 추출.
 *
 *   user_id  ──► Long
 *   isAdmin  ──► authorities에 ROLE_ADMIN 포함 여부
 */
public final class AuthContext {

    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private AuthContext() {}

    public static Long requireUserId(JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();
        Object raw = jwt.getClaim("user_id");
        if (raw == null) {
            throw new IllegalStateException("JWT에 user_id 클레임이 없습니다");
        }
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) return Long.parseLong(s);
        throw new IllegalStateException("user_id 클레임 형식이 잘못됨: " + raw.getClass());
    }

    public static boolean isAdmin(JwtAuthenticationToken auth) {
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (ROLE_ADMIN.equals(a.getAuthority())) return true;
        }
        return false;
    }
}
