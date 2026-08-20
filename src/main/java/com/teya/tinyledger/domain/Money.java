package com.teya.tinyledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount in a single currency.
 *
 * <p>The scale is normalised to the currency's minor unit on construction (2 for EUR, 0 for ISK,
 * 3 for KWD). BigDecimal.equals is scale-sensitive, so without this "1.0" and "1.00" would be
 * unequal and the record's generated equals would be wrong.
 */
public record Money(BigDecimal amount, Currency currency) {

    /** Rejects inputs like "1e999999999", which would need gigabytes to expand to a plain number. */
    private static final int MAX_INTEGER_DIGITS = 18;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        int minorUnits = currency.getDefaultFractionDigits();
        if (minorUnits < 0) {
            throw new LedgerException(ErrorCode.UNSUPPORTED_CURRENCY,
                    currency.getCurrencyCode() + " is not a spendable currency");
        }
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw new LedgerException(ErrorCode.AMOUNT_TOO_LARGE, "amount has too many digits");
        }
        if (amount.scale() > minorUnits) {
            throw new LedgerException(ErrorCode.INVALID_AMOUNT_SCALE,
                    currency.getCurrencyCode() + " allows at most " + minorUnits + " decimal place(s)");
        }
        amount = amount.setScale(minorUnits, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), currencyOf(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Currency currencyOf(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new LedgerException(ErrorCode.UNSUPPORTED_CURRENCY, "unknown currency " + code);
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negated() {
        return new Money(amount.negate(), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new LedgerException(ErrorCode.CURRENCY_MISMATCH,
                    "cannot combine " + currency.getCurrencyCode() + " and " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
