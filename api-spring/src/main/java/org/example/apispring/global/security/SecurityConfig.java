package org.example.apispring.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final GoogleOAuth2RequestResolver googleOAuth2RequestResolver;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/health", "/error").permitAll()
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers("/logout")) // 이후 API 정책에 맞춰 조정
                .cors(Customizer.withDefaults())
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(ep -> ep
                                .authorizationRequestResolver(googleOAuth2RequestResolver)
                        )
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureHandler(oAuth2LoginFailureHandler)
                )
                .logout(logout -> logout.logoutSuccessUrl("/"));

        return http.build();
    }
}
