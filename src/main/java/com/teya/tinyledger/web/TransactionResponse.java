package com.teya.tinyledger.web;

import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        long sequence,
        TransactionType type,
        BigDecimal amount,
        String currency,
        BigDecimal balanceAfter,
        Instant recordedAt,
        String reference) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.sequence(),
                transaction.type(),
                transaction.amount().amount(),
                transaction.amount().currency().getCurrencyCode(),
                transaction.balanceAfter().amount(),
                transaction.recordedAt(),
                transaction.reference());
    }
}
