package com.teya.tinyledger.domain;

import static com.teya.tinyledger.domain.TransactionType.DEPOSIT;
import static com.teya.tinyledger.domain.TransactionType.WITHDRAWAL;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * Spring serves requests on a thread pool, so the ledger is shared mutable state. These are the two
 * things that break if the balance check and the append are not done together.
 */
class AccountConcurrencyTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void racing_withdrawals_cannot_overdraw_the_account() throws Exception {
        Account account = newAccountWith("100.00");

        // 40 threads race for the 10 withdrawals of 10.00 that the balance can actually cover.
        AtomicInteger succeeded = new AtomicInteger();
        runTogether(40, () -> {
            try {
                account.record(WITHDRAWAL, Money.of("10.00", "EUR"), null, NOW);
                succeeded.incrementAndGet();
            } catch (LedgerException e) {
                assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
            }
        });

        assertThat(succeeded).hasValue(10);
        assertThat(account.balance()).isEqualTo(Money.of("0.00", "EUR"));
        assertThat(account.history()).hasSize(11);
    }

    @Test
    void the_balance_stays_equal_to_the_sum_of_the_history() throws Exception {
        Account account = newAccountWith("1000.00");

        runTogether(32, () -> {
            for (int i = 0; i < 25; i++) {
                account.record(DEPOSIT, Money.of("2.00", "EUR"), null, NOW);
                account.record(WITHDRAWAL, Money.of("1.00", "EUR"), null, NOW);
            }
        });

        Money sum = account.history().stream()
                .map(Transaction::signedAmount)
                .reduce(Money.zero(EUR), Money::plus);
        assertThat(account.balance()).isEqualTo(sum);

        List<Long> sequences = account.history().stream().map(Transaction::sequence).toList();
        assertThat(sequences).containsExactlyElementsOf(
                LongStream.rangeClosed(1, sequences.size()).boxed().toList());
    }

    private static Account newAccountWith(String opening) {
        Account account = new Account(UUID.randomUUID(), EUR, NOW);
        account.record(DEPOSIT, Money.of(opening, "EUR"), "opening balance", NOW);
        return account;
    }

    /** Starts every task at the same moment and rethrows anything unexpected. */
    private static void runTogether(int threads, Runnable task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
    }
}
