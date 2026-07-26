package com.spademoney.ledger.hold;

import java.time.OffsetDateTime;

/**
 * The hold is still marked ACTIVE but its authorization window has lapsed.
 *
 * Distinct from HoldNotActiveException on purpose: "your auth window closed" and
 * "someone already resolved this" are different things to tell a merchant, and
 * only the first one is the merchant's own fault. The row can still read ACTIVE
 * here because expiry is enforced by the expires_at predicate, not by the
 * sweeper -- a lapsed hold stops reserving funds long before any job relabels
 * it.
 */
public class HoldExpiredException extends RuntimeException {
    public HoldExpiredException(long holdId, OffsetDateTime expiredAt) {
        super("Hold " + holdId + " expired at " + expiredAt + "; it can no longer be captured");
    }
}
