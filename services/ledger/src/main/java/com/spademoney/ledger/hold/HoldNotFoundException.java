package com.spademoney.ledger.hold;

public class HoldNotFoundException extends RuntimeException {
    public HoldNotFoundException(long holdId) {
        super("No hold with id " + holdId);
    }
}
