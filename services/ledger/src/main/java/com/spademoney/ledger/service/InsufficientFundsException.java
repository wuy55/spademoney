package com.spademoney.ledger.service;

/**
 * The payer's AVAILABLE balance (posted minus active holds) is below the
 * requested amount. Typed rather than a bare IllegalArgumentException so a
 * client can distinguish "you are short" from "your request is malformed".
 */
public class InsufficientFundsException extends IllegalArgumentException {
    public InsufficientFundsException(long availableMinor, long requestedMinor) {
        super("Insufficient funds. Available: " + availableMinor + ", requested: " + requestedMinor);
    }
}
