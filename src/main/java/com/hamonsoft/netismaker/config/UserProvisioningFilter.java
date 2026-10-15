package com.hamonsoft.netismaker.config;

import com.hamonsoft.netismaker.controller.AuthContext;
import com.hamonsoft.netismaker.service.UserProvisioner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /api/** JWT 인증 뒤 com."user" 행을 보장한다(UserProvisioner).
 * @Component로 두면 서블릿 필터로 전역 등록되므로 SecurityConfig api 체인에서만 생성한다.
 */
public class UserProvisioningFilter extends OncePerRequestFilter {

    private final UserProvisioner provisioner;

    public UserProvisioningFilter(UserProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && isHumanUser(jwt)) {
            try {
                provisioner.ensureUser(AuthContext.requireUserId(jwt), jwt.getToken().getClaimAsString("email"));
            } catch (IllegalStateException ignored) {
                // username/sub/user_id 클레임이 없는 토큰 — 컨트롤러가 같은 예외로 처리한다
            }
        }
        chain.doFilter(request, response);
    }

    // client_credentials 토큰은 sub=client_id라 사용자 행을 만들면 안 된다 — 사용자 역할이 있는 토큰만
    private static boolean isHumanUser(Authentication auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_USER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
