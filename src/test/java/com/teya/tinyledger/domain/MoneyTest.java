package com.teya.tinyledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void amounts_that_differ_only_in_scale_are_the_same_money() {
        assertThat(Money.of("1.0", "EUR")).isEqualTo(Money.of("1.00", "EUR"));
    }

    @Test
    void each_currency_keeps_its_own_number_of_decimals() {
        assertThat(Money.of("10", "EUR")).hasToString("10.00 EUR");
        assertThat(Money.of("10", "ISK")).hasToString("10 ISK");
        assertThat(Money.of("10", "KWD")).hasToString("10.000 KWD");
    }

    @Test
    void more_decimals_than_the_currency_allows_are_rejected_rather_than_rounded() {
        assertThatThrownBy(() -> Money.of("1.005", "EUR"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_AMOUNT_SCALE);

        assertThatThrownBy(() -> Money.of("10.50", "ISK"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_AMOUNT_SCALE);
    }

    @Test
    void an_absurdly_large_amount_is_rejected_before_it_is_expanded() {
        assertThatThrownBy(() -> Money.of("1e999999999", "EUR"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AMOUNT_TOO_LARGE);
    }

    @Test
    void adds_and_subtracts() {
        assertThat(Money.of("10.50", "EUR").plus(Money.of("0.75", "EUR"))).isEqualTo(Money.of("11.25", "EUR"));
        assertThat(Money.of("10.50", "EUR").minus(Money.of("0.75", "EUR"))).isEqualTo(Money.of("9.75", "EUR"));
        assertThat(Money.of("10.50", "EUR").negated()).isEqualTo(Money.of("-10.50", "EUR"));
    }

    @Test
    void currencies_cannot_be_mixed() {
        assertThatThrownBy(() -> Money.of("1.00", "EUR").plus(Money.of("1.00", "GBP")))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void an_unknown_currency_code_is_rejected() {
        assertThatThrownBy(() -> Money.currencyOf("XYZ"))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.UNSUPPORTED_CURRENCY);
    }
}
