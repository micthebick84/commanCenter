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

    public static String requireUserId(JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();
        // com."user".user_id (VARCHAR(20))와 매핑되는 비즈니스 user_id를 우선.
        // netis-auth 발급 JWT는 username='admin', user_id='1' (내부 ID) — username이 com.user에 매핑됨.
        Object raw = jwt.getClaim("username");
        if (raw == null) raw = jwt.getSubject();           // 'sub'에도 username 동일
        if (raw == null) raw = jwt.getClaim("user_id");    // 최후 폴백
        if (raw == null) {
            throw new IllegalStateException("JWT에 username/sub/user_id 클레임이 없습니다");
        }
        return raw.toString();
    }

    public static boolean isAdmin(JwtAuthenticationToken auth) {
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (ROLE_ADMIN.equals(a.getAuthority())) return true;
        }
        return false;
    }
}
