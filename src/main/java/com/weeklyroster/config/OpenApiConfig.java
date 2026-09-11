package com.weeklyroster.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI weeklyRosterOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Weekly Roster Management System (WRMS) API")
                        .version("1.0.0")
                        .description("Production REST API and External Integration Layer for Workforce Roster Management")
                        .contact(new Contact().name("WRMS Engineering").email("rajatkumarmaury@gmail.com")))
                .addSecurityItem(new SecurityRequirement().addList("apiKeyAuth").addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("apiKeyAuth", new SecurityScheme()
                                .name("X-API-Key")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .description("Custom API key header for external partner integrations"))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Bearer authentication token for admin/employee sessions or external tokens")));
    }

    @Bean
    public GroupedOpenApi externalApi() {
        return GroupedOpenApi.builder()
                .group("external-v1")
                .displayName("External API v1 (Partner Integration)")
                .pathsToMatch("/api/external/v1/**")
                .build();
    }

    @Bean
    public GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder()
                .group("internal-wrms")
                .displayName("Internal WRMS Application API")
                .pathsToMatch("/api/**")
                .pathsToExclude("/api/external/**")
                .build();
    }
}
