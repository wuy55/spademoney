package com.spademoney.payments.reconciliation;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;

import com.spademoney.payments.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Payments' reconciliation, including the checks that have to leave the service
 * to get an answer.
 *
 * The remote checks are the reason this class exists. Everything else Payments
 * knows is in its own database and could be enforced by a constraint; whether a
 * transaction the saga claims to have created actually exists is a question only
 * the Ledger can answer, and across the boundary there is no foreign key that
 * can ask it. That is the cost of the split, paid here.
 */
@SpringBootTest
@AutoConfigureMockRestServiceServer
@Import(TestcontainersConfiguration.class)
class PaymentsReconciliationTest {

    @Autowired
    private PaymentsReconciliationService reconciliation;
    @Autowired
    private MockRestServiceServer ledger;
    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void reset() {
        jdbcClient.sql("TRUNCATE sagas, saga_steps, limit_consumptions, payment_limits CASCADE").update();
    }

    @AfterEach
    void everyScriptedCallWasMade() {
        ledger.verify();
    }

    @Test
    void aServiceWithNothingInFlightReconcilesCleanAndNamesItsChecks() {
        ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.failures()).isEmpty();
        assertThat(report.service()).isEqualTo("payments");
        assertThat(names(report)).containsExactlyInAnyOrder(
                "NO_STUCK_SAGAS", "NO_ESCALATED_SAGAS", "FAILED_SAGAS_RELEASED_THEIR_LIMIT",
                "NO_DANGLING_STEPS", "COMPLETED_PAYMENTS_EXIST_IN_LEDGER",
                "FAILED_PAYMENTS_RELEASED_THEIR_HOLD");
    }

    /**
     * A payment that has not advanced in a long time.
     *
     * The driver retries with backoff and gives up after a bounded number of
     * attempts, so this should be impossible -- which is exactly why it is
     * checked. "Impossible" rests on the driver running at all, and a dead
     * scheduler leaves every in-flight payment sitting still with nothing else
     * in the service noticing.
     */
    @Test
    void aSagaThatHasNotMovedInAgesIsReported() {
        UUID id = insertSaga("RUNNING", null, null, null);
        jdbcClient.sql("UPDATE sagas SET updated_at = now() - interval '1 hour' WHERE id = ?")
                .param(id).update();

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(failedNames(report)).containsExactly("NO_STUCK_SAGAS");
        assertThat(check(report, "NO_STUCK_SAGAS").detail()).contains(id.toString());
    }

    /**
     * The dead end the system refuses to hide: a compensation that could not
     * complete. Funds are reserved in the Ledger that Payments tried and failed
     * to release, and only a person can finish it.
     */
    @Test
    void aSagaWhoseCompensationFailedIsSurfacedAsNeedingAHuman() {
        insertSaga("FAILED", "COMPENSATION_FAILED", null, 900L);
        // A saga in this state is FAILED and still names a hold, so the remote
        // check reads it too -- and finds it captured, which is precisely how a
        // compensation comes to fail. Both findings are correct and both appear.
        expectHold(900L, "CAPTURED");

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(failedNames(report))
                .contains("NO_ESCALATED_SAGAS", "FAILED_PAYMENTS_RELEASED_THEIR_HOLD");
    }

    /**
     * A declined payment still holding the payer's cap.
     *
     * Nothing outside this service would ever notice: the payer is simply
     * refused later, for a reason with no error attached to it anywhere.
     */
    @Test
    void aFailedPaymentStillHoldingItsSpendingCapIsReported() {
        UUID id = insertSaga("FAILED", "INSUFFICIENT_FUNDS", null, null);
        jdbcClient.sql("""
                INSERT INTO limit_consumptions (saga_id, account_id, amount_minor, currency)
                VALUES (?, 11, 2500, 'USD')
                """).param(id).update();

        assertThat(failedNames(reconciliation.reconcile()))
                .containsExactly("FAILED_SAGAS_RELEASED_THEIR_LIMIT");
    }

    /** A released consumption is evidence a compensation ran, and passes. */
    @Test
    void aReleasedSpendingCapIsNotAFinding() {
        UUID id = insertSaga("COMPENSATED", "PAYMENT_LIMIT_EXCEEDED", null, null);
        jdbcClient.sql("""
                INSERT INTO limit_consumptions (saga_id, account_id, amount_minor, currency, released_at)
                VALUES (?, 11, 2500, 'USD', now())
                """).param(id).update();

        assertThat(reconciliation.reconcile().failures()).isEmpty();
    }

    // ----------------------------------------------------------- remote checks

    /**
     * The check the service boundary makes necessary.
     *
     * Payments says it captured; the Ledger has never heard of the transaction.
     * That would be money reported as moved that did not move -- the single
     * worst outcome this whole project is built to rule out. Inside one service
     * a foreign key would make it impossible; across the boundary nothing can,
     * so it has to be asked.
     */
    @Test
    void aCompletedPaymentTheLedgerCannotConfirmIsReported() {
        // COMPLETED, so only the transfer check reads it: the hold check looks
        // at failed sagas, and a completed payment's hold is supposed to be
        // captured.
        insertSaga("COMPLETED", null, 4242L, 7L);
        ledger.expect(requestTo("http://localhost:8080/transfers/4242"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NOT_FOUND\",\"message\":\"no such transfer\"}"));

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(failedNames(report)).contains("COMPLETED_PAYMENTS_EXIST_IN_LEDGER");
        assertThat(check(report, "COMPLETED_PAYMENTS_EXIST_IN_LEDGER").detail()).contains("4242");
    }

    @Test
    void aCompletedPaymentTheLedgerConfirmsPassesTheCheck() {
        insertSaga("COMPLETED", null, 4243L, null);
        ledger.expect(requestTo("http://localhost:8080/transfers/4243"))
                .andRespond(withSuccess("{\"transactionId\":4243,\"type\":\"CAPTURE\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(reconciliation.reconcile().failures()).isEmpty();
    }

    /**
     * The mirror, and the more dangerous direction: Payments reported a failure
     * and the Ledger still has the payer's funds reserved.
     */
    @Test
    void aFailedPaymentWhoseHoldIsStillActiveIsReported() {
        insertSaga("COMPENSATED", "PAYMENT_LIMIT_EXCEEDED", null, 91L);
        expectHold(91L, "ACTIVE");

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(failedNames(report)).containsExactly("FAILED_PAYMENTS_RELEASED_THEIR_HOLD");
        assertThat(check(report, "FAILED_PAYMENTS_RELEASED_THEIR_HOLD").detail())
                .contains("still ACTIVE");
    }

    /**
     * Worse still: reported as failed, and the money moved anyway.
     */
    @Test
    void aFailedPaymentWhoseHoldWasCapturedIsReported() {
        insertSaga("COMPENSATED", "PAYMENT_LIMIT_EXCEEDED", null, 92L);
        expectHold(92L, "CAPTURED");

        assertThat(check(reconciliation.reconcile(), "FAILED_PAYMENTS_RELEASED_THEIR_HOLD").detail())
                .contains("CAPTURED");
    }

    @Test
    void aFailedPaymentWhoseHoldWasVoidedPassesTheCheck() {
        insertSaga("COMPENSATED", "PAYMENT_LIMIT_EXCEEDED", null, 93L);
        expectHold(93L, "VOIDED");

        assertThat(reconciliation.reconcile().failures()).isEmpty();
    }

    // ------------------------------------------------------------- test setup

    private void expectHold(long holdId, String status) {
        ledger.expect(requestTo("http://localhost:8080/holds/" + holdId))
                .andRespond(withSuccess("""
                        {"holdId":%d,"accountId":11,"payeeAccountId":22,"amountMinor":2500,
                         "currency":"USD","status":"%s","expiresAt":"2030-01-01T00:00:00Z"}
                        """.formatted(holdId, status), MediaType.APPLICATION_JSON));
    }

    private UUID insertSaga(String status, String failureCode, Long transactionId, Long holdId) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                INSERT INTO sagas (id, idempotency_key, request_fingerprint, status,
                                   payer_account_id, payee_account_id, amount_minor, currency,
                                   hold_id, ledger_transaction_id, failure_code, failure_message)
                VALUES (?, ?, 'fp', ?, 11, 22, 2500, 'USD', ?, ?, ?, ?)
                """)
                .params(id, "key-" + id, status, holdId, transactionId,
                        failureCode, failureCode == null ? null : "because " + failureCode)
                .update();
        return id;
    }

    private static List<String> names(ReconciliationReport report) {
        return report.checks().stream().map(ReconciliationReport.Check::name).toList();
    }

    private static List<String> failedNames(ReconciliationReport report) {
        return report.failures().stream().map(ReconciliationReport.Check::name).toList();
    }

    private static ReconciliationReport.Check check(ReconciliationReport report, String name) {
        return report.checks().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No check named " + name));
    }
}
