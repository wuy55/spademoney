package com.spademoney.payments.ledger;

/**
 * The Ledger's error body: {"code": "...", "message": "..."}.
 *
 * Duplicated here rather than imported. Payments must not depend on the Ledger
 * artifact (see this module's pom), so the wire shapes it needs are restated at
 * the boundary. The cost is two records that look alike; the benefit is that a
 * refactor inside the Ledger cannot break Payments' compile, only its tests
 * against the published contract — which is exactly the failure you want, in
 * exactly the place you want it.
 */
public record LedgerError(String code, String message) {

    /** What to report when the Ledger fails without a parseable body. */
    static LedgerError unparseable() {
        return new LedgerError("UNPROCESSABLE", "Ledger returned an error with no readable body");
    }
}
