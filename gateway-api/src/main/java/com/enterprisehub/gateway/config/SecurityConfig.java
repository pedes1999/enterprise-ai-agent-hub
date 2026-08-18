package com.enterprisehub.gateway.config;

import com.enterprisehub.gateway.security.JwtAuthFilter;
import com.enterprisehub.gateway.security.PasswordChangeRequiredFilter;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

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
    private final PasswordChangeRequiredFilter passwordChangeRequiredFilter;
    private final CorsProperties corsProperties;

    public SecurityConfig(TenantResolvingFilter tenantResolvingFilter, JwtAuthFilter jwtAuthFilter,
                           PasswordChangeRequiredFilter passwordChangeRequiredFilter, CorsProperties corsProperties) {
        this.tenantResolvingFilter = tenantResolvingFilter;
        this.jwtAuthFilter = jwtAuthFilter;
        this.passwordChangeRequiredFilter = passwordChangeRequiredFilter;
        this.corsProperties = corsProperties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**", "/actuator/health").permitAll()
                .requestMatchers("/webhooks/**").permitAll() // signature validation happens in the webhook controller itself
                // API docs describe the surface, they don't expose it -- every endpoint
                // listed here still enforces its own auth when actually called.
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Spring Boot's default error handling forwards any unhandled
                // exception to /error to render the response. Without this,
                // that forwarded request re-enters the filter chain as an
                // unauthenticated request, anyRequest().authenticated() denies
                // it, and the client sees a bare, misleading 403 instead of
                // the real error status/body -- masking every uncaught 500
                // (and anything else) behind "Forbidden".
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            );

        // JwtAuthFilter populates the SecurityContext from the Bearer token,
        // running before the tenant filter since tenant resolution depends
        // on auth already having populated the SecurityContext.
        // PasswordChangeRequiredFilter runs right after it -- fail-fast
        // before tenant resolution or any handler code runs at all, if this
        // caller still needs to set their own password (see its javadoc).
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(passwordChangeRequiredFilter, JwtAuthFilter.class);
        http.addFilterAfter(tenantResolvingFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Every prior caller (Postman, CI) was non-browser, so this never
     * existed until the Angular frontend -- a genuine cross-origin browser
     * client. Deliberately a single explicit origin from configuration
     * (app.cors.allowed-origin), never "*": a wildcard origin combined with
     * credentialed requests (Authorization headers) would undercut the
     * tenant-isolation discipline the rest of this system is careful about
     * (RLS, scoped credential resolution, etc.) by letting any origin read
     * responses meant for one tenant's authenticated session.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(corsProperties.allowedOrigin()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
