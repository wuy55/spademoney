package com.spademoney.payments;

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
 * That the background jobs are actually scheduled.
 *
 * <h2>This test exists because the absence of one annotation shipped</h2>
 * {@code @EnableScheduling} was missing from {@code PaymentsApplication}. Every
 * saga test passed, because they all drive {@code SagaDriver.runOnce()}
 * themselves — which is the right way to test a state machine and is exactly why
 * none of them could notice that nothing ever calls it in production. The
 * symptom only appeared against the real compose stack: a payment accepted with
 * 202 and then sitting in RUNNING forever, with no error anywhere, because the
 * driver was never invoked.
 *
 * Same shape as the connect-vs-read timeout bug from session 6: the tests could
 * only exercise what they explicitly drove. The fix in both cases is a test that
 * asserts the wiring rather than the behaviour.
 *
 * <h2>Why it asserts registered tasks and not just the bean</h2>
 * The scheduler bean exists whether or not {@code @EnableScheduling} is present
 * — it is an ordinary {@code @Component}. What disappears without the annotation
 * is the post-processor that turns {@code @Scheduled} into a registered task. So
 * the assertion has to be about the task registry, which is the thing that was
 * actually missing.
 */
@SpringBootTest(properties = {
        "spademoney.saga.enabled=true",
        "spademoney.reconciliation.enabled=true"
})
@Import(TestcontainersConfiguration.class)
class SchedulingIsWiredTest {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @Test
    void theSagaDriverAndReconciliationAreActuallyScheduled() {
        Set<String> scheduled = scheduledTaskHolder.getScheduledTasks().stream()
                .map(ScheduledTask::toString)
                .collect(Collectors.toSet());

        assertThat(scheduled)
                .as("registered @Scheduled tasks: %s", scheduled)
                .isNotEmpty();

        assertThat(scheduled.stream().anyMatch(task -> task.contains("SagaScheduler")))
                .as("the saga driver must be on a timer; without it every payment "
                        + "is accepted and then never advances. Tasks: %s", scheduled)
                .isTrue();

        assertThat(scheduled.stream().anyMatch(task -> task.contains("ReconciliationScheduler")))
                .as("reconciliation must run on a timer, not only on demand. Tasks: %s", scheduled)
                .isTrue();
    }
}
