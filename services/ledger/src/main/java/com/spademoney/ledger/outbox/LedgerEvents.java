package com.spademoney.ledger.outbox;

import java.time.OffsetDateTime;

/**
 * The Ledger's published vocabulary: the event names and the exact shapes that
 * go on the wire.
 *
 * <h2>These are a contract, not internal types</h2>
 * Payments has its own copies of the shapes it cares about, restated in its own
 * package (ADR-007: no shared module). Renaming a field here is therefore a
 * published-contract change that breaks a consumer at runtime, which is why
 * every one of these records is flat, primitive and free of ledger internals.
 * Nothing here mentions {@code Money}, an exception type, or a row shape --
 * publishing an internal type is how a service boundary starts leaking.
 *
 * <h2>Events are facts, in the past tense</h2>
 * {@code HoldAuthorized}, not {@code AuthorizeHold}. An event records something
 * that already happened and committed; a consumer may not refuse it, only
 * decide what to do about it. Commands go the other way, over HTTP, and those
 * a service may reject -- which is exactly why the saga issues commands rather
 * than publishing them.
 */
public final class LedgerEvents {

    public static final String TRANSFER_POSTED = "TransferPosted";
    public static final String HOLD_AUTHORIZED = "HoldAuthorized";
    public static final String HOLD_CAPTURED = "HoldCaptured";
    public static final String HOLD_VOIDED = "HoldVoided";
    public static final String HOLD_EXPIRED = "HoldExpired";
    public static final String REFUND_POSTED = "RefundPosted";

    private LedgerEvents() {
    }

    public record TransferPosted(
            long transactionId,
            long fromAccountId,
            long toAccountId,
            long amountMinor,
            String currency) {
    }

    public record HoldAuthorized(
            long holdId,
            long accountId,
            long payeeAccountId,
            long amountMinor,
            String currency,
            OffsetDateTime expiresAt) {
    }

    /**
     * {@code releasedMinor} is the part of the authorization a partial capture
     * gave back. A consumer reconciling reserved-versus-settled needs both
     * numbers and cannot derive one from the other without the original hold.
     */
    public record HoldCaptured(
            long holdId,
            long transactionId,
            long accountId,
            long payeeAccountId,
            long capturedMinor,
            long releasedMinor,
            String currency) {
    }

    public record HoldVoided(
            long holdId,
            long accountId,
            long amountMinor,
            String currency) {
    }

    /**
     * Emitted by the sweeper. An expiry is housekeeping in this service -- the
     * funds stopped being reserved the moment the deadline passed, not when the
     * row was relabelled -- but it is still a fact a saga waiting on that hold
     * needs to hear.
     */
    public record HoldExpired(long holdId) {
    }

    public record RefundPosted(
            long transactionId,
            long reversesTransactionId,
            long amountMinor,
            String currency) {
    }
}
