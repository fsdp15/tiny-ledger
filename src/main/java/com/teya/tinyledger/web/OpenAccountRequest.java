package com.teya.tinyledger.web;

import jakarta.validation.constraints.Pattern;

/** Currency is optional; leave it out and the account is opened in the configured default. */
public record OpenAccountRequest(
        @Pattern(regexp = "[A-Za-z]{3}", message = "must be a three letter ISO 4217 code")
        String currency) {
}
