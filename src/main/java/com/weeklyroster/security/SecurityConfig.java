package com.weeklyroster.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.weeklyroster.entity.Role;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomUserDetailsService userDetailsService;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, CustomUserDetailsService userDetailsService) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/styles.css", "/app.js", "/favicon.ico", "/error").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/login/**", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/auth/me", "/api/auth/change-password", "/api/auth/logout").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers(HttpMethod.POST, "/api/leaves").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers(HttpMethod.POST, "/api/leaves/*/modification", "/api/leaves/*/cancellation").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers("/api/leaves/my/*", "/api/leaves/my/**").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers("/api/rosters/employee/*", "/api/rosters/employee/**").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers("/api/rosters/my-duty/today", "/api/rosters/effective-duty").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers("/api/notifications", "/api/notifications/**").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers("/api/activities", "/api/activities/**").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers("/api/profile-change-requests", "/api/profile-change-requests/**").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers("/api/employees/me", "/api/employees/me/**").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers(HttpMethod.GET, "/api/shifts", "/api/employees/*", "/api/rosters/cycle/*/export/*", "/api/dashboard/day-view", "/api/dashboard/employee-view").hasAnyAuthority(Role.ROLE_ADMIN.name(), Role.ROLE_EMPLOYEE.name())
                        .requestMatchers("/api/**").hasAuthority(Role.ROLE_ADMIN.name())
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                            response.getWriter().write("{\"timestamp\":\"" + java.time.LocalDateTime.now() + "\",\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication required to access this resource\",\"path\":\"" + request.getRequestURI() + "\"}");
                            response.getWriter().flush();
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType("application/json;charset=UTF-8");
                            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                            response.getWriter().write("{\"timestamp\":\"" + java.time.LocalDateTime.now() + "\",\"status\":403,\"error\":\"Forbidden\",\"message\":\"Access denied: Administrator privileges required\",\"path\":\"" + request.getRequestURI() + "\"}");
                            response.getWriter().flush();
                        }))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOriginPatterns(java.util.List.of("*"));
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(java.util.List.of("*"));
        configuration.setAllowCredentials(true);
        org.springframework.web.cors.UrlBasedCorsConfigurationSource source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
