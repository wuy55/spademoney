package com.spademoney.ledger.service;

public class CurrencyMismatchException extends IllegalArgumentException {
    public CurrencyMismatchException(long accountId, String accountCurrency, String requestedCurrency) {
        super("Account " + accountId + " is " + accountCurrency + ", request is " + requestedCurrency);
    }
}
