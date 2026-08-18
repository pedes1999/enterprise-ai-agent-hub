package com.enterprisehub.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    /**
     * Registers the JWT bearer scheme so Swagger UI's "Authorize" button attaches
     * {@code Authorization: Bearer <token>} to every try-it-out call -- without this,
     * every authenticated endpoint (i.e. everything except /auth/**) 401s from the UI
     * even with a valid token pasted in nowhere to put it.
     */
    @Bean
    public OpenAPI gatewayOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Enterprise AI Agent Hub API")
                .description("Multi-tenant platform for running LLM agents that clone, edit, test, "
                    + "and open pull requests against real repositories. Register via /auth/register "
                    + "or /auth/login to get a JWT, then Authorize below with it.")
                .version("v1"))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
            .components(new Components()
                .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                    .name(BEARER_SCHEME)
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")));
    }
}
