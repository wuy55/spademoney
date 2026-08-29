package com.spademoney.payments.ledger;

/**
 * Derives the Idempotency-Key that Payments sends to the Ledger.
 *
 * <h2>The caller's key is never forwarded</h2>
 * A client's {@code Idempotency-Key} names one operation in *Payments'* scope:
 * "this POST /payments". The keys sent to the Ledger name different operations
 * in the Ledger's scope: "authorize this hold", "capture it". Forwarding the
 * caller's key verbatim conflates them, and both directions are wrong:
 *
 * <ul>
 *   <li>One key would name two operations in two services, so a replay of the
 *       payment and a replay of a step become indistinguishable.</li>
 *   <li>A payment is three steps. A single forwarded key would make the second
 *       step collide with the first inside one Ledger scope — same key,
 *       different body, 422 IDEMPOTENCY_KEY_REUSED — and the saga would break
 *       on step two, every time.</li>
 * </ul>
 *
 * <h2>Deterministic, which is the whole point</h2>
 * {@code saga:{sagaId}:{step}}. The saga id is allocated from the caller's
 * Idempotency-Key through a UNIQUE constraint and persisted <em>before</em> the
 * first step runs, so:
 *
 * <ul>
 *   <li>a client retrying the same Idempotency-Key reaches the same saga, and
 *       therefore sends the same step keys;</li>
 *   <li>the driver retrying a step after a timeout sends the same key it sent
 *       the first time.</li>
 * </ul>
 *
 * Both cases become replays at the Ledger rather than second effects. That is
 * what closes the double-charge window this class carried, and documented, from
 * session 6 through session 8: the previous derivation minted a fresh UUID per
 * request, so a retry produced a second transfer.
 *
 * It is also what makes a 504 recoverable. In session 6 a read timeout was
 * unresolvable — Payments could not know whether the transfer posted and could
 * not safely retry. With a stable key the question stops mattering: resend, and
 * the Ledger either performs it or replays its own earlier answer.
 */
public final class LedgerIdempotencyKeys {

    private LedgerIdempotencyKeys() {
    }

    /**
     * @param sagaId the persisted saga id, not a per-attempt value
     * @param step   the step name, fixed by the saga plan
     */
    public static String forStep(String sagaId, String step) {
        return "saga:%s:%s".formatted(sagaId, step);
    }
}
