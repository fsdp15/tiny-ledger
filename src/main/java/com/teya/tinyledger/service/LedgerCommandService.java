package com.teya.tinyledger.service;

import com.teya.tinyledger.LedgerProperties;
import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.ErrorCode;
import com.teya.tinyledger.domain.LedgerException;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionType;
import com.teya.tinyledger.repository.AccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Everything that changes the ledger. Reads live in {@link LedgerQueryService}. */
@Service
public class LedgerCommandService {

    private final AccountRepository accounts;
    private final IdempotencyStore idempotency;
    private final LedgerProperties properties;
    private final Clock clock;

    public LedgerCommandService(AccountRepository accounts, IdempotencyStore idempotency,
                                LedgerProperties properties, Clock clock) {
        this.accounts = accounts;
        this.idempotency = idempotency;
        this.properties = properties;
        this.clock = clock;
    }

    /** A null currency code opens the account in the configured default. */
    public Account openAccount(String currencyCode) {
        Currency currency = Money.currencyOf(
                currencyCode == null ? properties.defaultCurrency() : currencyCode.toUpperCase(Locale.ROOT));
        Account account = new Account(UUID.randomUUID(), currency, clock.instant());
        accounts.save(account);
        return account;
    }

    public Transaction deposit(UUID accountId, BigDecimal amount, String reference, String idempotencyKey) {
        return record(accountId, TransactionType.DEPOSIT, amount, reference, idempotencyKey);
    }

    public Transaction withdraw(UUID accountId, BigDecimal amount, String reference, String idempotencyKey) {
        return record(accountId, TransactionType.WITHDRAWAL, amount, reference, idempotencyKey);
    }

    private Transaction record(UUID accountId, TransactionType type, BigDecimal requested,
                               String reference, String idempotencyKey) {
        // Required by the API, so a null here would be a bug that silently replays unrelated requests.
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        Account account = accounts.findById(accountId).orElseThrow(
                () -> new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "no account " + accountId));

        // The account's currency decides how many decimal places the amount may carry.
        Money amount = Money.of(requested, account.currency());
        if (amount.amount().compareTo(properties.maxTransactionAmount()) > 0) {
            throw new LedgerException(ErrorCode.AMOUNT_TOO_LARGE,
                    "single transaction limit is " + properties.maxTransactionAmount());
        }

        String fingerprint = type + "|" + amount + "|" + reference;
        Optional<Transaction> alreadyDone = idempotency.claim(accountId, idempotencyKey, fingerprint);
        if (alreadyDone.isPresent()) {
            return alreadyDone.get();
        }

        try {
            Transaction transaction = account.record(type, amount, reference, clock.instant());
            idempotency.complete(accountId, idempotencyKey, transaction);
            return transaction;
        } catch (LedgerException e) {
            // A rejected movement is thrown before anything is appended, so the key is safe to
            // reuse. Anything else may have failed after the money moved, so the key stays claimed
            // and a retry is refused rather than risking a second movement.
            idempotency.unclaim(accountId, idempotencyKey);
            throw e;
        }
    }
}
