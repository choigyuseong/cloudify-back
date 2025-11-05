package org.example.apispring.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static java.time.Clock.systemUTC;

@Configuration
public class TimeConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
