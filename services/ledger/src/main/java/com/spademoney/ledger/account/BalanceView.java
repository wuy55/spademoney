package com.spademoney.ledger.account;

/**
 * GET /accounts/{id}/balance.
 *
 * postedBalance is derived from the entries table, never a stored column.
 * availableBalance (posted minus active holds) is not yet modeled; until holds
 * exist it equals posted.
 */
public record BalanceView(
        Long accountId,
        String currency,
        long postedBalance) {
}