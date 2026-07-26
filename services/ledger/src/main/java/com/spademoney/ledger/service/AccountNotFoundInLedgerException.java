package com.spademoney.ledger.service;

/** One or both accounts named by a money-moving request do not exist. */
public class AccountNotFoundInLedgerException extends IllegalArgumentException {
    public AccountNotFoundInLedgerException(long a, long b) {
        super("One or both accounts not found: " + a + ", " + b);
    }
}
