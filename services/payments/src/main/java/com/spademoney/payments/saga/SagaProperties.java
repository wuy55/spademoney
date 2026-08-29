package com.spademoney.payments.saga;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Driver tuning.
 *
 * @param interval     how often the driver looks for work. Short, because it is
 *                     the ONLY path a saga advances on -- there is no separate
 *                     "kick it off synchronously" route, so this is also the
 *                     latency of a payment's first step.
 * @param batchSize    sagas claimed per tick.
 * @param lease        how long a claimed saga is left alone. Must comfortably
 *                     exceed one step's worst case (the Ledger's read timeout),
 *                     or a slow-but-succeeding call gets a second driver started
 *                     on the same step. That is survivable -- the step key is
 *                     deterministic, so the duplicate is a replay -- but it is
 *                     wasted work, and relying on the safety net for ordinary
 *                     operation is how the safety net stops being tested.
 * @param maxAttempts  how many times a retryable step is retried before the
 *                     saga gives up and compensates. Finite on purpose: a step
 *                     retried forever is a payment that never resolves, and a
 *                     customer would rather hear "declined" than nothing.
 * @param baseBackoff  first retry delay; doubles per attempt.
 * @param maxBackoff   ceiling, so the schedule stays inside human patience.
 * @param holdExpiry   how long the authorization is asked to live. It has to
 *                     outlast the saga's whole retry schedule with room to
 *                     spare: a hold that lapses mid-saga makes CAPTURE fail for
 *                     a reason nothing in Payments did wrong, and the funds are
 *                     released by the Ledger while the saga still believes it
 *                     has them. Fixed at step-creation time and persisted, so
 *                     every retry asks for the same deadline.
 */
@ConfigurationProperties("spademoney.saga")
public record SagaProperties(
        Duration interval,
        int batchSize,
        Duration lease,
        int maxAttempts,
        Duration baseBackoff,
        Duration maxBackoff,
        Duration holdExpiry) {

    public SagaProperties {
        interval = interval == null ? Duration.ofMillis(250) : interval;
        batchSize = batchSize <= 0 ? 20 : batchSize;
        lease = lease == null ? Duration.ofSeconds(30) : lease;
        maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
        baseBackoff = baseBackoff == null ? Duration.ofMillis(200) : baseBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(10) : maxBackoff;
        holdExpiry = holdExpiry == null ? Duration.ofMinutes(10) : holdExpiry;
    }
}
