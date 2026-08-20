package com.teya.tinyledger.domain;

public class LedgerException extends RuntimeException {

    private final ErrorCode code;

    public LedgerException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
