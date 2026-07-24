package com.spademoney.ledger.transfer;

/** One posting of a transaction, as exposed by GET /transfers/{id}. */
public record EntryView(
        Long accountId,
        String direction,
        long amountMinor,
        String currency) {
}