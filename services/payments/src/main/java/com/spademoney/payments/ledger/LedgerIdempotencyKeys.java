package com.spademoney.payments.ledger;

/**
 * Derives the Idempotency-Key that Payments sends to the Ledger.
 *
 * <h2>The caller's key is never forwarded</h2>
 * A client's {@code Idempotency-Key} names one operation in *Payments'* scope:
 * "this POST /payments". The key sent to the Ledger names a different
 * operation in the Ledger's scope: "this transfer". Forwarding the caller's key
 * verbatim conflates the two, and both directions are wrong:
 *
 * <ul>
 *   <li>One key would name two different operations in two services, so a
 *       replay of the payment and a replay of the transfer become
 *       indistinguishable.</li>
 *   <li>Once a payment is more than one Ledger call (Session 9: authorize, then
 *       capture), a single forwarded key would make the second step collide
 *       with the first inside one Ledger scope — 422 IDEMPOTENCY_KEY_REUSED,
 *       because the bodies differ.</li>
 * </ul>
 *
 * <h2>This derivation is a placeholder and is knowingly wrong</h2>
 * {@code paymentId} is minted fresh on every request, so a client retrying the
 * same {@code Idempotency-Key} produces a *new* Ledger key and a *second*
 * transfer. Session 6 does not fix that: Payments has no idempotency store yet,
 * and inventing half of one here would be a worse foundation than an obviously
 * missing one.
 *
 * Session 9 replaces this with {@code saga:{sagaId}:{step}}, where the saga id
 * is derived from the caller's key and persisted before the first step runs.
 * That makes the key deterministic across retries, which is what turns the
 * Ledger's idempotency contract into an actual exactly-once guarantee rather
 * than a decoration.
 */
public final class LedgerIdempotencyKeys {

    /** The one Ledger step this service performs today. Becomes a saga step name in Session 9. */
    private static final String TRANSFER_STEP = "ledger-transfer";

    private LedgerIdempotencyKeys() {
    }

    public static String forTransfer(String paymentId) {
        return "payment:%s:%s".formatted(paymentId, TRANSFER_STEP);
    }
}
