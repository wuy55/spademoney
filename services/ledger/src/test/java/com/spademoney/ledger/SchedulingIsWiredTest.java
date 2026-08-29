package com.spademoney.ledger;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the Ledger's background jobs are actually scheduled.
 *
 * The sibling of Payments' test of the same name, added after
 * {@code @EnableScheduling} turned out to be missing over there. The Ledger has
 * always had the annotation, but nothing asserted it — and the outbox relay's
 * failure mode without it is the quiet one: money keeps moving, events keep
 * accumulating, and no consumer ever hears about any of it. Every unit test
 * would still pass, because they all call {@code drainOnce()} directly.
 */
@SpringBootTest(properties = {
        "spademoney.outbox.relay.enabled=true",
        "spademoney.holds.sweeper.enabled=true",
        "spademoney.reconciliation.enabled=true"
})
@Import(TestcontainersConfiguration.class)
class SchedulingIsWiredTest {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    void theRelaySweeperAndReconciliationAreActuallyScheduled() {
        Set<String> scheduled = scheduledTaskHolder.getScheduledTasks().stream()
                .map(ScheduledTask::toString)
                .collect(Collectors.toSet());

        assertThat(scheduled.stream().anyMatch(task -> task.contains("OutboxRelayScheduler")))
                .as("the outbox relay must be on a timer; without it every event "
                        + "commits and none is ever published. Tasks: %s", scheduled)
                .isTrue();
        assertThat(scheduled.stream().anyMatch(task -> task.contains("HoldExpiryScheduler")))
                .as("the expiry sweeper must be on a timer. Tasks: %s", scheduled)
                .isTrue();
        assertThat(scheduled.stream().anyMatch(task -> task.contains("ReconciliationScheduler")))
                .as("reconciliation must run on a timer. Tasks: %s", scheduled)
                .isTrue();
    }
}
