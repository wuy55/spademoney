package com.spademoney.ledger.account;

/**
 * GET /accounts/{id}/balance.
 *
 * Both figures are derived from the entries and holds tables, never stored.
 *   postedBalance    = money that actually moved
 *   heldMinor        = sum of active, unexpired holds
 *   availableBalance = posted - held; what can be spent right now
 */
public record BalanceView(
        Long accountId,
        String currency,
        long postedBalance,
        long heldMinor,
        long availableBalance) {
}
