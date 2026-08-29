package com.spademoney.payments.saga;

import java.util.List;
import java.util.Map;

/**
 * The payment saga, written down: three forward steps and one compensation.
 *
 * <pre>
 *   AUTHORIZE      hold the payer's funds in the Ledger
 *   CONSUME_LIMIT  check and record the payer's cap, locally
 *   CAPTURE        settle the hold in the Ledger
 *
 *   compensation, in reverse, for whatever already succeeded:
 *   RELEASE_LIMIT  give the payer's cap back, locally
 *   VOID           release the hold in the Ledger
 * </pre>
 *
 * <h2>Why authorize comes before the local limit check</h2>
 * Checking the limit first would be cheaper and would need no compensation at
 * all — which is exactly why it is the wrong shape for this system to have.
 * More usefully, it is also the wrong order on its own merits: the most common
 * rejection by far is insufficient funds, and that answer lives in the Ledger.
 * Consuming a payer's cap for a payment the Ledger was going to refuse means
 * releasing it again on every declined attempt, so the cap would be wrong for
 * as long as any compensation was in flight.
 *
 * Authorizing first puts the cheap common refusal before any local state
 * changes. What it costs is the interesting part: the local step now sits
 * BETWEEN two remote effects, and there is no transaction that spans them. When
 * the limit check refuses, the hold in the Ledger is already real and has to be
 * undone by a compensating command. That is not an accident of the ordering; it
 * is the thing a saga is for, and it is the one path this milestone implements
 * properly rather than five implemented vaguely.
 *
 * <h2>Compensation is not rollback</h2>
 * VOID does not undo AUTHORIZE. It is a new, forward operation that happens to
 * have the opposite effect, and it is visible in the Ledger as its own event.
 * Nothing is erased — the hold existed, and the history says so. Rollback is a
 * property of a single transaction; across services the best available is
 * "post the opposite", and pretending otherwise is how people convince
 * themselves they have distributed transactions.
 */
public final class SagaPlan {

    public static final String AUTHORIZE = "AUTHORIZE";
    public static final String CONSUME_LIMIT = "CONSUME_LIMIT";
    public static final String CAPTURE = "CAPTURE";
    public static final String VOID = "VOID";
    public static final String RELEASE_LIMIT = "RELEASE_LIMIT";

    public static final String FORWARD = "FORWARD";
    public static final String COMPENSATION = "COMPENSATION";

    /** In order. The driver runs the first one that is not yet SUCCEEDED. */
    public static final List<String> FORWARD_STEPS = List.of(AUTHORIZE, CONSUME_LIMIT, CAPTURE);

    /**
     * What undoes what.
     *
     * CAPTURE deliberately has no entry. Once the money has moved the only
     * "compensation" available is a refund, and a refund is a business decision
     * with its own authorization, not something a retry loop should issue on its
     * own initiative at 4am. So capture is the saga's point of no return:
     * succeed and it is COMPLETED, fail and everything before it is undone.
     */
    private static final Map<String, String> COMPENSATORS = Map.of(
            AUTHORIZE, VOID,
            CONSUME_LIMIT, RELEASE_LIMIT);

    /** The order compensations run in, once a saga has turned around. */
    private static final List<String> COMPENSATION_ORDER = List.of(RELEASE_LIMIT, VOID);

    private SagaPlan() {
    }

    /**
     * The compensations a saga owes, in the order they should run.
     *
     * Reverse of the forward order, and that is not cosmetic. Undoing forwards
     * can release a constraint a later step still depends on; undoing backwards
     * unwinds the stack the way it was built. Here it means the payer's cap is
     * released before the hold is voided, so there is never an instant where the
     * cap is free but the funds are still reserved -- a customer retrying
     * straight after a decline would otherwise pass the cap check and then fail
     * on funds their own abandoned hold was still holding.
     *
     * Only steps that actually SUCCEEDED are compensated. A saga that never got
     * a hold has nothing to void, and issuing a void for a hold that does not
     * exist turns a clean failure into a 404 and a stuck saga.
     */
    public static List<String> compensationsFor(List<String> succeededForwardSteps) {
        return FORWARD_STEPS.reversed().stream()
                .filter(succeededForwardSteps::contains)
                .map(COMPENSATORS::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public static int sequenceOf(String step) {
        int forward = FORWARD_STEPS.indexOf(step);
        if (forward >= 0) {
            return forward;
        }
        return FORWARD_STEPS.size() + COMPENSATION_ORDER.indexOf(step);
    }

    public static String kindOf(String step) {
        return FORWARD_STEPS.contains(step) ? FORWARD : COMPENSATION;
    }
}
