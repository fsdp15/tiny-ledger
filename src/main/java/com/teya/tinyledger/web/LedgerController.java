package com.teya.tinyledger.web;

import com.teya.tinyledger.domain.TransactionType;
import com.teya.tinyledger.service.LedgerCommandService;
import com.teya.tinyledger.service.LedgerQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Ledger")
public class LedgerController {

    private final LedgerCommandService commands;
    private final LedgerQueryService queries;

    public LedgerController(LedgerCommandService commands, LedgerQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open an account, in the default currency unless another is given")
    public AccountResponse openAccount(@Valid @RequestBody(required = false) OpenAccountRequest request) {
        return AccountResponse.from(commands.openAccount(request == null ? null : request.currency()));
    }

    @PostMapping("/{accountId}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Pay money in")
    public TransactionResponse deposit(
            @PathVariable UUID accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MovementRequest request) {

        return TransactionResponse.from(
                commands.deposit(accountId, request.amount(), request.reference(), idempotencyKey));
    }

    @PostMapping("/{accountId}/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Take money out, if the balance covers it")
    public TransactionResponse withdraw(
            @PathVariable UUID accountId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MovementRequest request) {

        return TransactionResponse.from(
                commands.withdraw(accountId, request.amount(), request.reference(), idempotencyKey));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Current balance")
    public BalanceResponse balance(@PathVariable UUID accountId) {
        return BalanceResponse.from(queries.account(accountId));
    }

    @GetMapping("/{accountId}/transactions/{transactionId}")
    @Operation(summary = "One transaction from this account")
    public TransactionResponse transaction(@PathVariable UUID accountId, @PathVariable UUID transactionId) {
        return TransactionResponse.from(queries.transaction(accountId, transactionId));
    }

    @GetMapping("/{accountId}/transactions")
    @Operation(summary = "Transaction history, newest first")
    public TransactionPageResponse transactions(
            @PathVariable UUID accountId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit) {

        return TransactionPageResponse.from(queries.history(accountId, type, from, to, cursor, limit));
    }
}
