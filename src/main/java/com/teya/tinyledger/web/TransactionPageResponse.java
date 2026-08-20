package com.teya.tinyledger.web;

import com.teya.tinyledger.service.LedgerQueryService;
import java.util.List;

/** {@code nextCursor} is null when there is nothing older left to read. */
public record TransactionPageResponse(
        List<TransactionResponse> transactions,
        Long nextCursor,
        boolean hasMore) {

    public static TransactionPageResponse from(LedgerQueryService.Page page) {
        return new TransactionPageResponse(
                page.transactions().stream().map(TransactionResponse::from).toList(),
                page.nextCursor(),
                page.hasMore());
    }
}
