package com.teya.tinyledger.web;

import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.Money;
import java.math.BigDecimal;
import java.util.UUID;

public record BalanceResponse(UUID accountId, String currency, BigDecimal balance) {

    public static BalanceResponse from(Account account) {
        Money balance = account.balance();
        return new BalanceResponse(account.id(), balance.currency().getCurrencyCode(), balance.amount());
    }
}
