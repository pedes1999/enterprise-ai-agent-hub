package com.enterprisehub.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables @PreAuthorize on controller/service methods. Role checks read
 * "ROLE_ADMIN" etc. off the GrantedAuthority set on the SecurityContext,
 * which JwtAuthFilter populates as "ROLE_" + PlatformPrincipal.role() --
 * see JwtAuthFilter for where that authority actually gets set.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
