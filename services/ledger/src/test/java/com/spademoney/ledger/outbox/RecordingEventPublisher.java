package com.spademoney.ledger.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * An {@link EventPublisher} that remembers what it was given and can be told to
 * fail on cue.
 *
 * The failure hook is the reason this exists rather than a Mockito stub: the
 * relay's most important behaviour is what it does to the events <em>after</em>
 * the one that failed, and that needs a publisher that fails on the third call
 * and succeeds on every other. Arranging that against a real broker means
 * breaking a real broker on demand.
 */
class RecordingEventPublisher implements EventPublisher {

    private final List<OutboxRecord> published = new ArrayList<>();
    private Predicate<OutboxRecord> failWhen = record -> false;

    @Override
    public void publish(OutboxRecord record) {
        if (failWhen.test(record)) {
            throw new IllegalStateException("broker refused " + record.eventType());
        }
        published.add(record);
    }

    List<OutboxRecord> published() {
        return List.copyOf(published);
    }

    List<String> publishedEventTypes() {
        return published.stream().map(OutboxRecord::eventType).toList();
    }

    void failOn(Predicate<OutboxRecord> predicate) {
        this.failWhen = predicate;
    }

    void succeedAlways() {
        this.failWhen = record -> false;
    }

    void reset() {
        published.clear();
        succeedAlways();
    }
}
