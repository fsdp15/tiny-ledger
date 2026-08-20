package com.teya.tinyledger.web;

import com.teya.tinyledger.domain.ErrorCode;
import com.teya.tinyledger.domain.LedgerException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Errors come back as RFC 9457 problem details, with a stable {@code code} for clients to switch on. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ProblemDetail> handleLedger(LedgerException e) {
        return problem(statusFor(e.code()), e.code().name(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", e.getHeaderName() + " is required");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleUnreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "the request could not be read");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(status.getReasonPhrase());
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }

    private static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INSUFFICIENT_FUNDS, CURRENCY_MISMATCH -> HttpStatus.UNPROCESSABLE_ENTITY;
            case IDEMPOTENCY_KEY_REUSED, IDEMPOTENT_REQUEST_UNRESOLVED -> HttpStatus.CONFLICT;
            case AMOUNT_NOT_POSITIVE, AMOUNT_TOO_LARGE, INVALID_AMOUNT_SCALE,
                 UNSUPPORTED_CURRENCY, INVALID_CURSOR -> HttpStatus.BAD_REQUEST;
        };
    }
}
