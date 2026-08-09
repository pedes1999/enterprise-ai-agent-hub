package com.enterprisehub.gateway.config;

import com.enterprisehub.gateway.security.JwtAuthFilter;
import com.enterprisehub.gateway.security.TenantResolvingFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Baseline security posture for the gateway.
 *
 * Principles baked in from day one:
 *  - Stateless (no HTTP sessions) — every request authenticates via JWT / platform API key,
 *    since callers are CI pipelines, webhooks, and CLIs, not browsers with cookies.
 *  - CSRF disabled because there is no session/cookie-based auth to protect.
 *  - Everything denied by default except explicitly listed public endpoints.
 *    JwtAuthFilter populates the SecurityContext per-request based on the
 *    validated Bearer token.
 */
@Configuration
public class SecurityConfig {

    private final TenantResolvingFilter tenantResolvingFilter;
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(TenantResolvingFilter tenantResolvingFilter, JwtAuthFilter jwtAuthFilter) {
        this.tenantResolvingFilter = tenantResolvingFilter;
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**", "/actuator/health").permitAll()
                .requestMatchers("/webhooks/**").permitAll() // signature validation happens in the webhook controller itself
                .anyRequest().authenticated()
            );

        // JwtAuthFilter populates the SecurityContext from the Bearer token,
        // running before the tenant filter since tenant resolution depends
        // on auth already having populated the SecurityContext.
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(tenantResolvingFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
