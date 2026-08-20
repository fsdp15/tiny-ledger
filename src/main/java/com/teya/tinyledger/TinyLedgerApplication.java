package com.teya.tinyledger;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(LedgerProperties.class)
@OpenAPIDefinition(info = @Info(title = "Tiny Ledger", version = "1.0.0"))
public class TinyLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TinyLedgerApplication.class, args);
    }

    /** Injected rather than calling Instant.now() directly, so tests can pin the time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
