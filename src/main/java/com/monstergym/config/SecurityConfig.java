package com.monstergym.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monstergym.common.ApiErrorBody;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper,
                                           SecurityContextRepository contextRepository) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Cierre de las conexiones SSE de /api/fingerprint/events (la petición original ya se autorizó)
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                // Primer arranque: crear el administrador (el controlador lo niega si ya existe uno)
                .requestMatchers(HttpMethod.GET, "/api/setup").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/setup/admin").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            // Cookie XSRF-TOKEN legible por JS; el frontend la devuelve en la cabecera X-XSRF-TOKEN
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .securityContext(sc -> sc.securityContextRepository(contextRepository))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    writeError(res, mapper, HttpServletResponse.SC_UNAUTHORIZED, ApiErrorBody.NO_AUTENTICADO))
                .accessDeniedHandler((req, res, e) ->
                    writeError(res, mapper, HttpServletResponse.SC_FORBIDDEN,
                        e instanceof CsrfException ? ApiErrorBody.CSRF_INVALIDO : ApiErrorBody.SIN_PERMISO)))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void writeError(HttpServletResponse res, ObjectMapper mapper, int status, ApiErrorBody body)
            throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        mapper.writeValue(res.getOutputStream(), body);
    }
}
