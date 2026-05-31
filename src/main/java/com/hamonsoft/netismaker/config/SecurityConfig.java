package com.hamonsoft.netismaker.config;

import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * 두 개의 SecurityFilterChain:
 *
 *   /worker/**  ──► WorkerApiKeyFilter (X-Worker-API-Key, ROLE_WORKER)
 *   /api/**     ──► JWT (netis-auth, ROLE_USER / ROLE_ADMIN)
 *   나머지       ──► permitAll (actuator/health, error)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("api")
public class SecurityConfig {

    private final CorsConfigurationSource corsConfigurationSource;
    private final WorkerApiKeyFilter workerApiKeyFilter;

    public SecurityConfig(CorsConfigurationSource corsConfigurationSource,
                          WorkerApiKeyFilter workerApiKeyFilter) {
        this.corsConfigurationSource = corsConfigurationSource;
        this.workerApiKeyFilter = workerApiKeyFilter;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain workerFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/worker/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().hasAuthority(WorkerApiKeyFilter.AUTHORITY)
                )
                .addFilterBefore(workerApiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                );
        return http.build();
    }

    /**
     * EventSource는 Authorization 헤더를 못 실으므로 SSE용으로 ?access_token= 쿼리 파라미터를 허용.
     * 내부도구 전제 — URL에 토큰 노출 가능성은 의도적 트레이드오프(평문 시크릿 정책과 동일 선상).
     */
    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        resolver.setAllowUriQueryParameter(true);
        return resolver;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object authoritiesClaim = jwt.getClaim("authorities");
            if (authoritiesClaim instanceof Collection<?> authorities) {
                return authorities.stream()
                        .map(Object::toString)
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        });
        return converter;
    }
}
