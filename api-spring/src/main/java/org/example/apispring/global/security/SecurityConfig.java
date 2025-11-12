package org.example.apispring.global.security;

import lombok.RequiredArgsConstructor;
import org.example.apispring.global.security.jwt.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final JsonAuthenticationEntryPoint entryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;
    private final GoogleOAuth2RequestResolver googleOAuth2RequestResolver;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/error",
                                "/actuator/health",
                                "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html",
                                "/oauth2/**", "/login/oauth2/**",
                                "/api/auth/refresh"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            String uri = req.getRequestURI();
                            if (uri.startsWith("/api/")) {
                                // API → 401 JSON
                                entryPoint.commence(req, res, e);
                            } else {
                                // 웹 라우트 → 구글 인가로
                                new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/google")
                                        .commence(req, res, e);
                            }
                        })
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(ep -> ep.authorizationRequestResolver(googleOAuth2RequestResolver))
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler(oAuth2LoginFailureHandler)
                );

        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}