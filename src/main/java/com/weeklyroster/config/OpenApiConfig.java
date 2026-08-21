package com.weeklyroster.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {
	@Bean
	public OpenAPI weeklyRosterOpenApi() {
		String scheme = "bearerAuth";
		return new OpenAPI().info(new Info().title("Weekly Roster Management System").version("1.0.0"))
				.addSecurityItem(new SecurityRequirement().addList(scheme))
				.components(new Components().addSecuritySchemes(scheme, new SecurityScheme().name(scheme)
						.type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
	}
}
