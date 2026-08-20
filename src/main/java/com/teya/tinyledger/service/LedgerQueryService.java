package com.teya.tinyledger.service;

import com.teya.tinyledger.LedgerProperties;
import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.ErrorCode;
import com.teya.tinyledger.domain.LedgerException;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.domain.TransactionType;
import com.teya.tinyledger.repository.AccountRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Everything that reads the ledger. Writes live in {@link LedgerCommandService}. */
@Service
public class LedgerQueryService {

    public record Page(List<Transaction> transactions, Long nextCursor, boolean hasMore) {
    }

    private final AccountRepository accounts;
    private final LedgerProperties properties;

    public LedgerQueryService(AccountRepository accounts, LedgerProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    public Account account(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(
                () -> new LedgerException(ErrorCode.ACCOUNT_NOT_FOUND, "no account " + accountId));
    }

    /**
     * Deliberately scoped to one account rather than looked up globally, so that holding a
     * transaction id is not enough to read someone else's ledger.
     */
    public Transaction transaction(UUID accountId, UUID transactionId) {
        return account(accountId).history().stream()
                .filter(transaction -> transaction.id().equals(transactionId))
                .findFirst()
                .orElseThrow(() -> new LedgerException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "no transaction " + transactionId + " on this account"));
    }

    /**
     * Newest first, paged by sequence number rather than by offset. Entries are appended while a
     * client is paging, and an offset window would silently skip or repeat rows as it shifts.
     */
    public Page history(UUID accountId, TransactionType type, Instant from, Instant to,
                        Long cursor, Integer limit) {
        if (cursor != null && cursor < 1) {
            throw new LedgerException(ErrorCode.INVALID_CURSOR, "cursor must be a positive sequence number");
        }

        int size = pageSize(limit);
        List<Transaction> history = account(accountId).history();

        // One extra row tells us whether another page exists without counting the whole history.
        List<Transaction> page = new ArrayList<>(size + 1);
        for (int i = history.size() - 1; i >= 0 && page.size() <= size; i--) {
            Transaction transaction = history.get(i);
            if (cursor != null && transaction.sequence() >= cursor) {
                continue;
            }
            if (type != null && transaction.type() != type) {
                continue;
            }
            if (from != null && transaction.recordedAt().isBefore(from)) {
                continue;
            }
            if (to != null && transaction.recordedAt().isAfter(to)) {
                continue;
            }
            page.add(transaction);
        }

        boolean hasMore = page.size() > size;
        if (hasMore) {
            page = page.subList(0, size);
        }
        return new Page(List.copyOf(page), hasMore ? page.getLast().sequence() : null, hasMore);
    }

    private int pageSize(Integer limit) {
        return limit == null ? properties.defaultPageSize() : Math.clamp(limit, 1, properties.maxPageSize());
    }
}
