package com.teya.tinyledger;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        String defaultCurrency,
        BigDecimal maxTransactionAmount,
        int defaultPageSize,
        int maxPageSize,
        Duration idempotencyTtl,
        int maxIdempotencyKeys) {
}
