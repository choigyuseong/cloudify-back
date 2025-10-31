package org.example.apispring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync  // ✅ 이거 추가해야 @Async 작동
public class ApiSpringApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiSpringApplication.class, args);
    }

}
