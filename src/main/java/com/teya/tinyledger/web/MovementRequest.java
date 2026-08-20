package com.teya.tinyledger.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The body of a deposit or a withdrawal. The currency is not repeated here: an account holds one
 * currency for life, so the movement can only ever be in that one.
 */
public record MovementRequest(
        @NotNull
        @DecimalMin(value = "0", inclusive = false, message = "must be greater than zero")
        BigDecimal amount,

        @Size(max = 140)
        String reference) {
}
