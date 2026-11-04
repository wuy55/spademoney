package com.spademoney.payments.inbox;

/**
 * How many records are parked on the dead-letter topic right now.
 *
 * An interface for one reason: reconciliation has to ask this, and the
 * reconciliation tests run with no broker. A hand-written fake behind this seam
 * lets them set the answer per test, while the one implementation that talks
 * to Kafka is exercised against a real Redpanda in
 * {@code LedgerEventConsumerIntegrationTest}.
 */
public interface DeadLetterDepth {

    /**
     * @return the number of records currently retained on the dead-letter topic
     * @throws RuntimeException if the broker could not be asked; the caller
     *                          turns that into a finding, not a pass
     */
    long depth();
}
