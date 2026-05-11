package com.hamonsoft.netismaker.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 워커 API 인증 필터.
 *
 *   요청 (/worker/**) ──► X-Worker-API-Key 헤더 검증 ──► ROLE_WORKER 부여
 *
 * 공유 시크릿 모델 (DESIGN §6 P11). V2에서 mTLS로 강화 예정.
 */
@Component
public class WorkerApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Worker-API-Key";
    public static final String AUTHORITY = "ROLE_WORKER";
    public static final String PRINCIPAL = "worker";

    @Value("${app.worker.api-key}")
    private String expectedKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (expectedKey != null && !expectedKey.isBlank() && expectedKey.equals(provided)) {
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    PRINCIPAL, null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 워커 경로에만 적용
        return !request.getRequestURI().startsWith("/worker/");
    }
}
