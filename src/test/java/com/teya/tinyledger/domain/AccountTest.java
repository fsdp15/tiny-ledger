package com.teya.tinyledger.domain;

import static com.teya.tinyledger.domain.TransactionType.DEPOSIT;
import static com.teya.tinyledger.domain.TransactionType.WITHDRAWAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account(UUID.randomUUID(), Currency.getInstance("EUR"), NOW);
    }

    @Test
    void a_new_account_is_empty() {
        assertThat(account.balance()).isEqualTo(Money.of("0", "EUR"));
        assertThat(account.history()).isEmpty();
    }

    @Test
    void deposits_add_up() {
        account.record(DEPOSIT, Money.of("100.00", "EUR"), "salary", NOW);
        account.record(DEPOSIT, Money.of("0.55", "EUR"), "refund", NOW);

        assertThat(account.balance()).isEqualTo(Money.of("100.55", "EUR"));
    }

    @Test
    void the_entire_balance_can_be_withdrawn() {
        account.record(DEPOSIT, Money.of("100.00", "EUR"), null, NOW);
        account.record(WITHDRAWAL, Money.of("100.00", "EUR"), null, NOW);

        assertThat(account.balance()).isEqualTo(Money.of("0.00", "EUR"));
    }

    @Test
    void a_withdrawal_beyond_the_balance_is_refused_and_leaves_the_ledger_untouched() {
        account.record(DEPOSIT, Money.of("50.00", "EUR"), null, NOW);

        assertThatThrownBy(() -> account.record(WITHDRAWAL, Money.of("50.01", "EUR"), null, NOW))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INSUFFICIENT_FUNDS);

        assertThat(account.balance()).isEqualTo(Money.of("50.00", "EUR"));
        assertThat(account.history()).hasSize(1);
    }

    @Test
    void amounts_must_be_greater_than_zero() {
        assertThatThrownBy(() -> account.record(DEPOSIT, Money.of("0.00", "EUR"), null, NOW))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AMOUNT_NOT_POSITIVE);

        assertThatThrownBy(() -> account.record(DEPOSIT, Money.of("-5.00", "EUR"), null, NOW))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AMOUNT_NOT_POSITIVE);
    }

    @Test
    void money_in_another_currency_is_refused() {
        assertThatThrownBy(() -> account.record(DEPOSIT, Money.of("10.00", "GBP"), null, NOW))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void every_entry_carries_its_position_and_the_balance_it_left_behind() {
        account.record(DEPOSIT, Money.of("100.00", "EUR"), "salary", NOW);
        account.record(WITHDRAWAL, Money.of("30.00", "EUR"), "rent", NOW);

        assertThat(account.history()).extracting(
                        Transaction::sequence, Transaction::type, Transaction::amount,
                        Transaction::balanceAfter, Transaction::reference)
                .containsExactly(
                        tuple(1L, DEPOSIT, Money.of("100.00", "EUR"), Money.of("100.00", "EUR"), "salary"),
                        tuple(2L, WITHDRAWAL, Money.of("30.00", "EUR"), Money.of("70.00", "EUR"), "rent"));
    }
}
