package com.hamonsoft.netismaker.controller;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 로그인 사용자 정보 + role.
 *
 *   GET /api/me ──► JWT 디코드 결과를 그대로 반환
 *
 * 사용처:
 *   - 프론트엔드가 로그인 직후 호출해서 user_id, role 추출
 *   - DESIGN §12 사용자 API 명세
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    @GetMapping
    public Map<String, Object> me(JwtAuthenticationToken auth) {
        Jwt jwt = auth.getToken();
        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        Map<String, Object> body = new HashMap<>();
        body.put("user_id",     jwt.getClaim("user_id"));
        body.put("username",    jwt.getClaim("username"));
        body.put("email",       jwt.getClaim("email"));
        body.put("authorities", authorities);
        body.put("expires_at",  jwt.getExpiresAt());
        return body;
    }
}
