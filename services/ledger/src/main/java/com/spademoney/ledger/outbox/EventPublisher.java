package com.spademoney.ledger.outbox;

/**
 * The relay's one dependency on the outside world.
 *
 * <h2>Why a port rather than using KafkaTemplate directly</h2>
 * Not for the usual "swap the implementation" reason -- the broker choice is
 * fixed (ADR: Redpanda, Kafka API). It exists so the relay's <em>ordering and
 * failure</em> behaviour can be tested without a broker: a recording
 * implementation can be told to fail on the third event and the test can then
 * assert that events four and five stayed unpublished. Provoking that against a
 * real broker means breaking a real broker on cue.
 *
 * The real implementation is still proven against a real Redpanda, in
 * {@code OutboxRelayIntegrationTest}. One seam, two kinds of test, and neither
 * is asked to do the other's job.
 */
public interface EventPublisher {

    /**
     * Publish one event and do not return until the broker has acknowledged it.
     *
     * <h2>Blocking is the feature</h2>
     * The relay marks a row published only after this returns normally. If this
     * were fire-and-forget, the mark would record an intention rather than a
     * fact, and a broker that dropped the send would leave an event that was
     * never delivered and never will be -- silent loss, which is the one failure
     * mode the outbox exists to rule out. Waiting for the ack is what makes the
     * published flag mean something.
     *
     * @throws RuntimeException if the broker did not acknowledge. The row stays
     *                          unpublished and is retried on the next tick.
     */
    void publish(OutboxRecord record);
}
