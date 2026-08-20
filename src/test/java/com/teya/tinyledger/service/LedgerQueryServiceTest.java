package com.teya.tinyledger.service;

import static com.teya.tinyledger.domain.TransactionType.DEPOSIT;
import static com.teya.tinyledger.domain.TransactionType.WITHDRAWAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teya.tinyledger.LedgerProperties;
import com.teya.tinyledger.domain.Account;
import com.teya.tinyledger.domain.ErrorCode;
import com.teya.tinyledger.domain.LedgerException;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.repository.AccountRepository;
import com.teya.tinyledger.repository.InMemoryAccountRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerQueryServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");
    private static final LedgerProperties PROPERTIES = new LedgerProperties(
            "EUR", new BigDecimal("1000000.00"), 20, 10, Duration.ofHours(24), 1000);

    private AccountRepository accounts;
    private LedgerQueryService queries;
    private Account account;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccountRepository();
        queries = new LedgerQueryService(accounts, PROPERTIES);
        account = new Account(UUID.randomUUID(), Currency.getInstance("EUR"), START);
        accounts.save(account);
    }

    @Test
    void paging_walks_the_whole_history_newest_first_and_repeats_nothing() {
        deposits(25);

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        do {
            LedgerQueryService.Page page = queries.history(account.id(), null, null, null, cursor, 10);
            page.transactions().forEach(transaction -> seen.add(transaction.sequence()));
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).containsExactlyElementsOf(
                LongStream.rangeClosed(1, 25).boxed().sorted(Comparator.reverseOrder()).toList());
    }

    @Test
    void the_last_page_reports_that_there_is_nothing_more() {
        deposits(3);

        LedgerQueryService.Page page = queries.history(account.id(), null, null, null, null, 10);

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.transactions()).hasSize(3);
    }

    @Test
    void the_history_can_be_narrowed_to_one_kind_of_movement() {
        deposits(3);
        account.record(WITHDRAWAL, Money.of("1.00", "EUR"), "w", START.plusSeconds(100));

        LedgerQueryService.Page page = queries.history(account.id(), WITHDRAWAL, null, null, null, null);

        assertThat(page.transactions()).extracting(Transaction::reference).containsExactly("w");
    }

    @Test
    void the_history_can_be_narrowed_to_a_time_window() {
        deposits(5);

        LedgerQueryService.Page page = queries.history(
                account.id(), null, START.plusSeconds(2), START.plusSeconds(4), null, null);

        assertThat(page.transactions()).extracting(Transaction::reference)
                .containsExactly("deposit 4", "deposit 3", "deposit 2");
    }

    @Test
    void an_oversized_limit_is_capped_at_the_configured_maximum() {
        deposits(25);

        LedgerQueryService.Page page = queries.history(account.id(), null, null, null, null, 5000);

        assertThat(page.transactions()).hasSize(PROPERTIES.maxPageSize());
    }

    @Test
    void a_nonsense_cursor_is_rejected() {
        assertThatThrownBy(() -> queries.history(account.id(), null, null, null, 0L, null))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_CURSOR);
    }

    @Test
    void a_single_transaction_can_be_read_back_by_its_id() {
        deposits(2);
        Transaction second = account.history().getLast();

        assertThat(queries.transaction(account.id(), second.id())).isEqualTo(second);
    }

    @Test
    void a_transaction_cannot_be_read_through_a_different_account() {
        deposits(1);
        Transaction mine = account.history().getFirst();

        Account other = new Account(UUID.randomUUID(), Currency.getInstance("EUR"), START);
        accounts.save(other);

        assertThatThrownBy(() -> queries.transaction(other.id(), mine.id()))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.TRANSACTION_NOT_FOUND);
    }

    @Test
    void an_unknown_account_is_reported_as_not_found() {
        assertThatThrownBy(() -> queries.history(UUID.randomUUID(), null, null, null, null, null))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.ACCOUNT_NOT_FOUND);
    }

    /** Deposit n times, one second apart, so the time window filter has something to bite on. */
    private void deposits(int count) {
        for (int i = 1; i <= count; i++) {
            account.record(DEPOSIT, Money.of("1.00", "EUR"), "deposit " + i, START.plusSeconds(i));
        }
    }
}
