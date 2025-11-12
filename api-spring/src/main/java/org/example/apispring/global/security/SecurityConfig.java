package org.example.apispring.global.security;

import lombok.RequiredArgsConstructor;
import org.example.apispring.global.security.jwt.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final JsonAuthenticationEntryPoint entryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;

    // 🔒 OAuth2 관련 기능은 DB 마이그레이션 테스트 중 비활성화
    // private final GoogleOAuth2RequestResolver googleOAuth2RequestResolver;
    // private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    // private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 비활성화 (REST API 기본)
                .csrf(csrf -> csrf.disable())

                // CORS 허용
                .cors(Customizer.withDefaults())

                // 세션 사용하지 않음 (JWT 방식)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 예외 처리 핸들러 지정
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )

                // 요청 경로별 권한 설정
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/actuator/health", "/error",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                // OAuth 경로들도 임시로 permitAll (비활성 상태)
                                "/oauth2/**", "/login/oauth2/**",
                                "/api/auth/refresh",
                                // ✅ Swagger 및 CSV→DB 마이그레이션 테스트용 허용
                                "/api/**"
                        ).permitAll()
                        .anyRequest().permitAll()
                );

        // ✅ OAuth2 로그인 완전 비활성화 (이 블록 주석 처리)
        /*
        .oauth2Login(oauth -> oauth
                .authorizationEndpoint(ep -> ep.authorizationRequestResolver(googleOAuth2RequestResolver))
                .successHandler(oAuth2LoginSuccessHandler)
                .failureHandler(oAuth2LoginFailureHandler)
        );
        */

        // JWT 필터 추가 (단, 현재 모든 요청은 permitAll 상태)
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
