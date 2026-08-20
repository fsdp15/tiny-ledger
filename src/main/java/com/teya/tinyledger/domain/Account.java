package com.teya.tinyledger.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An account together with its ledger.
 *
 * <p>The history is the source of truth. The balance is not stored separately, it is the running
 * total carried on the most recent entry, so the two can never drift apart.
 */
public class Account {

    private final UUID id;
    private final Currency currency;
    private final Instant openedAt;

    // Held across the balance check and the append. Without it two concurrent withdrawals can both
    // see the old balance, both pass the check, and overdraw the account. It is private rather than
    // synchronizing on the account itself, which callers could lock from outside and interfere with.
    private final ReentrantLock lock = new ReentrantLock();

    // Replaced, never mutated in place. A reader gets a consistent balance and history from a single
    // volatile read and is never blocked by a write in flight.
    private volatile List<Transaction> history = List.of();

    public Account(UUID id, Currency currency, Instant openedAt) {
        this.id = id;
        this.currency = currency;
        this.openedAt = openedAt;
    }

    public UUID id() {
        return id;
    }

    public Currency currency() {
        return currency;
    }

    public Instant openedAt() {
        return openedAt;
    }

    /** Newest entry last. */
    public List<Transaction> history() {
        return history;
    }

    public Money balance() {
        List<Transaction> current = history;
        return current.isEmpty() ? Money.zero(currency) : current.getLast().balanceAfter();
    }

    public Transaction record(TransactionType type, Money amount, String reference, Instant at) {
        if (!amount.isPositive()) {
            throw new LedgerException(ErrorCode.AMOUNT_NOT_POSITIVE, "amount must be greater than zero");
        }
        if (!amount.currency().equals(currency)) {
            throw new LedgerException(ErrorCode.CURRENCY_MISMATCH,
                    "account is held in " + currency.getCurrencyCode());
        }

        lock.lock();
        try {
            Money current = balance();
            Money balanceAfter = type == TransactionType.DEPOSIT ? current.plus(amount) : current.minus(amount);
            if (balanceAfter.isNegative()) {
                throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS,
                        "balance is " + current + ", cannot withdraw " + amount);
            }

            Transaction transaction = new Transaction(
                    UUID.randomUUID(), history.size() + 1L, type, amount, balanceAfter, at, reference);

            List<Transaction> updated = new ArrayList<>(history);
            updated.add(transaction);
            history = Collections.unmodifiableList(updated);
            return transaction;
        } finally {
            lock.unlock();
        }
    }
}
