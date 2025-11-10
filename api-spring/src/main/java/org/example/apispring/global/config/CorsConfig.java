package org.example.apispring.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Value("${cors.allowed-headers}")
    private String allowedHeaders;

    @Value("${cors.allowed-methods}")
    private String allowedMethods;

    @Value("${cors.allow-credentials:true}")
    private boolean allowCredentials;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var cfg = new CorsConfiguration();

        for (String o : allowedOrigins.split(",")) {
            String origin = o.trim();
            if (!origin.isEmpty()) cfg.addAllowedOrigin(origin);
        }
        for (String h : allowedHeaders.split(",")) cfg.addAllowedHeader(h.trim());
        for (String m : allowedMethods.split(",")) cfg.addAllowedMethod(m.trim());

        cfg.setAllowCredentials(allowCredentials);
        cfg.setMaxAge(3600L);

        var src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
