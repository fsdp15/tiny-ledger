package com.teya.tinyledger.web;

import com.teya.tinyledger.domain.Account;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID accountId, String currency, BigDecimal balance, Instant openedAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.id(),
                account.currency().getCurrencyCode(),
                account.balance().amount(),
                account.openedAt());
    }
}
