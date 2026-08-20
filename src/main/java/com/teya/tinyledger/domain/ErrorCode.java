package com.teya.tinyledger.domain;

/** Machine-readable reason a request was rejected. Mapped to HTTP status codes in the web layer. */
public enum ErrorCode {
    ACCOUNT_NOT_FOUND,
    TRANSACTION_NOT_FOUND,
    INSUFFICIENT_FUNDS,
    AMOUNT_NOT_POSITIVE,
    AMOUNT_TOO_LARGE,
    INVALID_AMOUNT_SCALE,
    CURRENCY_MISMATCH,
    UNSUPPORTED_CURRENCY,
    INVALID_CURSOR,
    IDEMPOTENCY_KEY_REUSED,
    IDEMPOTENT_REQUEST_UNRESOLVED
}
