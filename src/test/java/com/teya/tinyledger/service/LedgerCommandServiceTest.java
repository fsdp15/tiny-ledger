package com.teya.tinyledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.teya.tinyledger.LedgerProperties;
import com.teya.tinyledger.domain.ErrorCode;
import com.teya.tinyledger.domain.LedgerException;
import com.teya.tinyledger.domain.Money;
import com.teya.tinyledger.domain.Transaction;
import com.teya.tinyledger.repository.AccountRepository;
import com.teya.tinyledger.repository.InMemoryAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerCommandServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC);
    private static final LedgerProperties PROPERTIES = new LedgerProperties(
            "EUR", new BigDecimal("1000000.00"), 20, 100, Duration.ofHours(24), 1000);

    private AccountRepository accounts;
    private LedgerCommandService service;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccountRepository();
        service = new LedgerCommandService(accounts, new IdempotencyStore(PROPERTIES, CLOCK), PROPERTIES, CLOCK);
        accountId = service.openAccount("EUR").id();
    }

    @Test
    void a_repeated_idempotency_key_replays_the_first_result_without_moving_money_again() {
        Transaction first = service.deposit(accountId, amount("100.00"), "rent", "key-1");
        Transaction replay = service.deposit(accountId, amount("100.00"), "rent", "key-1");

        assertThat(replay).isEqualTo(first);
        assertThat(balanceOf(accountId)).isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    void reusing_a_key_for_a_different_request_is_a_client_bug_and_is_rejected() {
        service.deposit(accountId, amount("100.00"), "rent", "key-1");

        assertThatThrownBy(() -> service.deposit(accountId, amount("250.00"), "rent", "key-1"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void two_identical_deposits_under_different_keys_are_two_deposits() {
        service.deposit(accountId, amount("100.00"), "rent", "key-1");
        service.deposit(accountId, amount("100.00"), "rent", "key-2");

        assertThat(balanceOf(accountId)).isEqualTo(Money.of("200.00", "EUR"));
    }

    @Test
    void a_key_is_freed_when_the_movement_fails_so_the_client_can_try_again() {
        assertThatThrownBy(() -> service.withdraw(accountId, amount("10.00"), null, "key-1"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INSUFFICIENT_FUNDS);

        service.deposit(accountId, amount("50.00"), null, "key-2");
        service.withdraw(accountId, amount("10.00"), null, "key-1");

        assertThat(balanceOf(accountId)).isEqualTo(Money.of("40.00", "EUR"));
    }

    @Test
    void keys_belong_to_one_account_and_do_not_collide_across_accounts() {
        UUID other = service.openAccount("EUR").id();

        service.deposit(accountId, amount("100.00"), null, "key-1");
        service.deposit(other, amount("100.00"), null, "key-1");

        assertThat(balanceOf(accountId)).isEqualTo(Money.of("100.00", "EUR"));
        assertThat(balanceOf(other)).isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    void a_key_stays_claimed_when_the_failure_comes_after_the_money_moved() {
        LedgerCommandService service = new LedgerCommandService(
                accounts, storeThatFailsToRecordOutcomes(), PROPERTIES, CLOCK);
        UUID account = service.openAccount("EUR").id();

        assertThatThrownBy(() -> service.deposit(account, amount("100.00"), "rent", "key-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(balanceOf(account)).isEqualTo(Money.of("100.00", "EUR"));

        // Retrying must not deposit again, because we cannot prove the first one did not land.
        assertThatThrownBy(() -> service.deposit(account, amount("100.00"), "rent", "key-1"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IDEMPOTENT_REQUEST_UNRESOLVED);
        assertThat(balanceOf(account)).isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    void an_account_opened_without_a_currency_uses_the_configured_default() {
        UUID account = service.openAccount(null).id();

        assertThat(accounts.findById(account).orElseThrow().currency())
                .isEqualTo(Currency.getInstance("EUR"));
    }

    @Test
    void the_amount_takes_the_accounts_currency_and_must_fit_its_decimals() {
        UUID krona = service.openAccount("ISK").id();

        service.deposit(krona, amount("1500"), null, "key-1");
        assertThat(balanceOf(krona)).isEqualTo(Money.of("1500", "ISK"));

        assertThatThrownBy(() -> service.deposit(krona, amount("10.50"), null, "key-2"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_AMOUNT_SCALE);
    }

    @Test
    void a_movement_over_the_configured_limit_is_rejected() {
        assertThatThrownBy(() -> service.deposit(accountId, amount("1000000.01"), null, "key-1"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AMOUNT_TOO_LARGE);
    }

    @Test
    void an_unknown_account_is_reported_as_not_found() {
        assertThatThrownBy(() -> service.deposit(UUID.randomUUID(), amount("10.00"), null, "key-1"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.ACCOUNT_NOT_FOUND);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    /** A store that lets the movement happen but always fails to remember that it did. */
    private static IdempotencyStore storeThatFailsToRecordOutcomes() {
        return new IdempotencyStore(PROPERTIES, CLOCK) {
            @Override
            public void complete(UUID accountId, String key, Transaction result) {
                throw new IllegalStateException("store unavailable");
            }
        };
    }

    private Money balanceOf(UUID id) {
        return accounts.findById(id).orElseThrow().balance();
    }
}
