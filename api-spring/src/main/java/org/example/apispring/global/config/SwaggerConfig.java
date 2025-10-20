package org.example.apispring.global.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info = @Info(title = "Cloudify API", version = "v1"),
        servers = @Server(url = "/"),
        security = {
                // 전역 보안 요구(쿠키 기반). 특정 엔드포인트에서만 걸고 싶으면 컨트롤러/메서드에 @SecurityRequirement 사용
                @SecurityRequirement(name = "cookieAuth")
        }
)
@SecurityScheme(
        name = "cookieAuth",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "AT" // 우리가 발급하는 AccessToken 쿠키 이름
)
@Configuration
public class SwaggerConfig {

    @Bean
    public GroupedOpenApi api() {
        return GroupedOpenApi.builder()
                .group("cloudify")
                .pathsToMatch("/api/**")         // 문서화할 경로 선택
                .build();
    }
}
