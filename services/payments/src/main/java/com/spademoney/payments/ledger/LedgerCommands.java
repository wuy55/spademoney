package com.spademoney.payments.ledger;

import java.time.OffsetDateTime;

/**
 * The wire shapes Payments exchanges with the Ledger, restated here rather than
 * imported.
 *
 * Payments must not depend on the Ledger artifact (see this module's pom), so
 * the handful of shapes it needs live at the boundary. The cost is a few records
 * that resemble the Ledger's own; the benefit is that a refactor inside the
 * Ledger cannot break Payments' compile, only its tests against the published
 * contract — which is the failure you want, in the place you want it.
 *
 * Note the vocabularies are allowed to differ. Payments says payer/payee. Where
 * the Ledger's field names differ, the translation happens in exactly one
 * place: the factory that builds the command, whose output the saga then
 * persists byte-for-byte.
 */
public final class LedgerCommands {

    private LedgerCommands() {
    }

    /** Body of POST /holds. */
    public record Authorize(
            long payerAccountId,
            long payeeAccountId,
            long amountMinor,
            String currency,
            long expiresInSeconds) {
    }

    /** Body of POST /holds/{id}/capture. */
    public record Capture(long amountMinor) {
    }

    /** 201 body of POST /holds, and 200 body of POST /holds/{id}/void. */
    public record HoldResult(
            Long holdId,
            Long accountId,
            Long payeeAccountId,
            long amountMinor,
            String currency,
            String status,
            OffsetDateTime expiresAt) {
    }

    /** 200 body of POST /holds/{id}/capture. */
    public record CaptureResult(
            Long holdId,
            Long transactionId,
            long capturedMinor,
            long releasedMinor,
            String currency,
            String status) {
    }
}
