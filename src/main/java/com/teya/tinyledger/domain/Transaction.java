package com.teya.tinyledger.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable entry in an account's ledger.
 *
 * <p>Ordering comes from {@code sequence}, not {@code recordedAt}: timestamps tie under load and
 * clocks can move backwards. It also serves as the pagination cursor.
 */
public record Transaction(
        UUID id,
        long sequence,
        TransactionType type,
        Money amount,
        Money balanceAfter,
        Instant recordedAt,
        String reference) {

    /** Positive for a deposit, negative for a withdrawal, so a balance can be rebuilt by summing. */
    public Money signedAmount() {
        return type == TransactionType.DEPOSIT ? amount : amount.negated();
    }
}
