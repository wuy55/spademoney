package com.spademoney.payments.saga;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter.
 *
 * <h2>Exponential</h2>
 * The failures worth retrying are transient: a restarting Ledger, a momentary
 * network fault. Retrying those immediately is the worst possible response --
 * it puts maximum load on a dependency at the exact moment it has least to
 * give, and a service coming back up gets knocked over by the queue that formed
 * while it was down. Doubling the wait gives it room to recover.
 *
 * <h2>Jitter, which is the part people leave out</h2>
 * Without jitter, everything that failed at the same moment retries at the same
 * moment. A Ledger restart fails fifty in-flight sagas simultaneously, and a
 * pure exponential schedule then sends all fifty again at t+200ms, all fifty at
 * t+400ms, and so on: the outage synchronises the clients into a thundering
 * herd, and the retries themselves become the second outage.
 *
 * Randomising each delay across a window breaks the synchronisation. This uses
 * "full jitter" -- a uniform draw from [0, computed] rather than
 * computed ± a slice -- which spreads retries widest and is the variant AWS's
 * write-up found best. The cost is that an individual retry can come back very
 * quickly; the benefit is that fifty of them do not come back together.
 *
 * A floor keeps a draw near zero from becoming a hot loop.
 */
final class SagaBackoff {

    private SagaBackoff() {
    }

    /**
     * @param attempt 1 for the first retry
     * @return how long to wait before the next attempt
     */
    static Duration delayFor(int attempt, SagaProperties properties) {
        long base = properties.baseBackoff().toMillis();
        long ceiling = properties.maxBackoff().toMillis();

        // Shift rather than Math.pow, and cap the exponent: attempt is bounded
        // by maxAttempts in practice, but a shift of 64 wraps around to 1 rather
        // than overflowing to something obviously wrong, which is the kind of
        // bug that only shows up on a service that has been failing all night.
        int exponent = Math.min(Math.max(attempt - 1, 0), 20);
        long uncapped = base << exponent;
        long window = Math.min(uncapped <= 0 ? ceiling : uncapped, ceiling);

        long jittered = ThreadLocalRandom.current().nextLong(window + 1);
        return Duration.ofMillis(Math.max(jittered, base / 2));
    }
}
