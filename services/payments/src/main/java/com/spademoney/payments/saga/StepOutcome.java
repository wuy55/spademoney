package com.spademoney.payments.saga;

/**
 * What happened when a step ran, in the only three flavours the driver can act
 * on differently.
 *
 * The taxonomy is the design. A step executor is not allowed to return "it
 * failed" — it has to say whether trying again could help, because that single
 * distinction decides between a retry, a compensation, and a payment left
 * hanging. It is the same distinction {@code LedgerClient} draws between 502 and
 * 504, carried up to where a decision gets made.
 */
public sealed interface StepOutcome {

    /** The step took effect. {@code result} is stored on the step row. */
    record Succeeded(Object result) implements StepOutcome {
    }

    /**
     * Nothing conclusive happened, or something transient went wrong. Try again
     * later, with the same idempotency key and the same persisted body — so a
     * retry is a replay if the first attempt did in fact land.
     *
     * A read timeout belongs here, and that is the payoff of the deterministic
     * key. Before it existed, "the Ledger may or may not have processed this"
     * had no safe response at all.
     */
    record Retry(String reason) implements StepOutcome {
    }

    /**
     * This will never succeed: the Ledger refused it, or a local rule did.
     * Retrying is pointless and the saga turns around.
     */
    record Terminal(String code, String message) implements StepOutcome {
    }
}
