package com.spademoney.payments.payment;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;

import com.spademoney.payments.TestcontainersConfiguration;
import com.spademoney.payments.limit.PaymentLimitService;
import com.spademoney.payments.saga.PaymentSaga;
import com.spademoney.payments.saga.SagaDriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The saga, driven a tick at a time against a scripted Ledger.
 *
 * The Ledger is {@code MockRestServiceServer}, and that is a decision rather
 * than a shortcut. What needs proving here is how the driver reacts to each
 * possible answer — a 422, a 500, a read timeout — and a real Ledger cannot be
 * asked to produce those on cue. The real end-to-end run is the compose smoke
 * and chaos scripts.
 *
 * The driver's scheduled trigger is off (see the test overrides), so each test
 * calls {@code runOnce()} and can assert on the state <em>between</em> steps.
 * "The hold exists and the capture has not run yet" is the interesting state,
 * and a background thread would race every assertion about it.
 */
@SpringBootTest
@AutoConfigureMockRestServiceServer
@Import(TestcontainersConfiguration.class)
class PaymentSagaTest {

    private static final long PAYER = 11L;
    private static final long PAYEE = 22L;
    private static final long AMOUNT = 2_500L;

    @Autowired
    private PaymentService payments;
    @Autowired
    private SagaDriver driver;
    @Autowired
    private PaymentLimitService limits;
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

    // ------------------------------------------------------------ happy path

    @Test
    void aPaymentAuthorizesConsumesItsLimitAndCaptures() {
        expectAuthorize(withHold(77L, "ACTIVE"));
        expectCapture(77L, withCapture(77L, 501L));

        PaymentView accepted = start("key-happy");
        assertThat(accepted.status()).isEqualTo(PaymentView.PENDING);

        // Tick 1: authorize. Tick 2: the local limit. Tick 3: capture.
        // Tick 4: nothing left to do, so the saga completes.
        drive(4);

        PaymentView done = reload(accepted);
        assertThat(done.sagaStatus()).isEqualTo(PaymentSaga.COMPLETED);
        assertThat(done.status()).isEqualTo(PaymentView.SUCCEEDED);
        assertThat(done.holdId()).isEqualTo(77L);
        assertThat(done.ledgerTransactionId()).isEqualTo(501L);
        assertThat(stepStatuses(done)).containsExactly(
                "AUTHORIZE=SUCCEEDED", "CONSUME_LIMIT=SUCCEEDED", "CAPTURE=SUCCEEDED");
    }

    /**
     * One tick, one step. The saga is only ever one step further along than the
     * last tick left it, which is what lets a crash resume from a known point
     * rather than from the middle of a sequence nobody recorded.
     */
    @Test
    void afterOneTickOnlyTheAuthorizationHasHappened() {
        expectAuthorize(withHold(78L, "ACTIVE"));

        PaymentView accepted = start("key-one-step");
        drive(1);

        PaymentView after = reload(accepted);
        assertThat(after.holdId()).isEqualTo(78L);
        assertThat(after.ledgerTransactionId()).isNull();
        assertThat(stepStatuses(after)).containsExactly("AUTHORIZE=SUCCEEDED");
    }

    // ---------------------------------------------------- deterministic keys

    /**
     * The fix for the double-charge window this project carried on purpose from
     * session 6 to session 8.
     *
     * Every step's key is {@code saga:{sagaId}:{step}} and the saga id is
     * persisted before any step runs, so the key a retry sends is the key the
     * first attempt sent. The Ledger's idempotency contract then makes the retry
     * a replay instead of a second effect. The old derivation minted a fresh
     * UUID per request, so a client retry produced a second transfer.
     */
    @Test
    void everyStepCarriesAKeyDerivedFromTheSagaAndItsStepName() {
        PaymentView accepted = start("key-deterministic");
        String sagaId = accepted.paymentId();

        ledger.expect(requestTo("http://localhost:8080/holds"))
                .andExpect(header("Idempotency-Key", "saga:" + sagaId + ":AUTHORIZE"))
                .andRespond(withHold(79L, "ACTIVE"));
        drive(1);
        ledger.reset();

        ledger.expect(requestTo("http://localhost:8080/holds/79/capture"))
                .andExpect(header("Idempotency-Key", "saga:" + sagaId + ":CAPTURE"))
                .andRespond(withCapture(79L, 502L));
        drive(2);
    }

    /**
     * A retry resends the body that was persisted when the step was created, not
     * one rebuilt from current state.
     *
     * The Ledger fingerprints request bodies and answers 422
     * IDEMPOTENCY_KEY_REUSED when a known key arrives with a different one. A
     * recomputed body would therefore not merely be untidy — it would wedge the
     * saga permanently, failing in a way that looks like a client bug rather
     * than like an outage.
     */
    @Test
    void aRetriedStepResendsTheIdenticalBody() {
        expectAuthorize(withServerError());
        PaymentView accepted = start("key-same-body");
        drive(1);

        String persisted = jdbcClient.sql("""
                SELECT command::text FROM saga_steps
                 WHERE saga_id = ? AND step = 'AUTHORIZE'
                """).param(UUID.fromString(accepted.paymentId())).query(String.class).single();

        ledger.reset();
        ledger.expect(requestTo("http://localhost:8080/holds"))
                .andExpect(jsonPath("$.payerAccountId").value((int) PAYER))
                .andExpect(jsonPath("$.amountMinor").value((int) AMOUNT))
                .andRespond(withHold(80L, "ACTIVE"));
        drive(1);

        String afterRetry = jdbcClient.sql("""
                SELECT command::text FROM saga_steps
                 WHERE saga_id = ? AND step = 'AUTHORIZE'
                """).param(UUID.fromString(accepted.paymentId())).query(String.class).single();
        assertThat(afterRetry).isEqualTo(persisted);
    }

    // ------------------------------------------------------------- retryable

    /**
     * A 5xx means the request was not processed, so retrying is plainly safe.
     */
    @Test
    void aLedgerServerErrorIsRetriedRatherThanFailingThePayment() {
        expectAuthorize(withServerError());
        PaymentView accepted = start("key-5xx");
        drive(1);

        PaymentView after = reload(accepted);
        assertThat(after.sagaStatus()).isEqualTo(PaymentSaga.RUNNING);
        assertThat(after.steps().getFirst().status()).isEqualTo("PENDING");
        assertThat(after.steps().getFirst().attempts()).isEqualTo(1);
        assertThat(after.steps().getFirst().lastError()).contains("ledger unavailable");
    }

    /**
     * The milestone in one test.
     *
     * A read timeout leaves Payments genuinely unable to say whether the hold
     * was placed — that ambiguity is not resolved and cannot be. What changed is
     * that it stopped mattering: the retry carries the same key and the same
     * body, so if the first attempt did land, the Ledger replays its own answer
     * and nothing happens twice. In session 6 this exact situation was a 504 and
     * a dead end.
     */
    @Test
    void aReadTimeoutIsRetriedBecauseTheRetryIsAReplay() {
        expectAuthorize(withException(new SocketTimeoutException("read timed out")));
        PaymentView accepted = start("key-timeout");
        drive(1);

        PaymentView after = reload(accepted);
        assertThat(after.sagaStatus()).isEqualTo(PaymentSaga.RUNNING);
        assertThat(after.steps().getFirst().lastError()).contains("retry is a replay");

        ledger.reset();
        expectAuthorize(withHold(81L, "ACTIVE"));
        drive(1);
        assertThat(reload(accepted).holdId()).isEqualTo(81L);
    }

    /**
     * Retries are bounded. A payment that never resolves is worse for everyone
     * than a decline, so an exhausted step is terminal and the saga unwinds.
     */
    @Test
    void aStepThatKeepsFailingEventuallyGivesUpAndTheSagaFails() {
        ledger.expect(ExpectedCount.times(5), requestTo("http://localhost:8080/holds"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        PaymentView accepted = start("key-exhausted");
        drive(6);

        PaymentView after = reload(accepted);
        assertThat(after.sagaStatus()).isEqualTo(PaymentSaga.FAILED);
        assertThat(after.failureCode()).isEqualTo("STEP_RETRIES_EXHAUSTED");
        // Nothing succeeded, so there is nothing to undo: a clean decline rather
        // than a compensation.
        assertThat(after.steps()).hasSize(1);
    }

    // -------------------------------------------------------------- terminal

    @Test
    void aLedgerRejectionEndsThePaymentWithTheLedgersOwnErrorCode() {
        ledger.expect(requestTo("http://localhost:8080/holds"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"INSUFFICIENT_FUNDS","message":"available 100, requested 2500"}
                                """));

        PaymentView accepted = start("key-declined");
        drive(1);

        PaymentView after = reload(accepted);
        assertThat(after.sagaStatus()).isEqualTo(PaymentSaga.FAILED);
        assertThat(after.status()).isEqualTo(PaymentView.FAILED);
        assertThat(after.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    // ---------------------------------------------------------- compensation

    /**
     * The compensation path, end to end, and the reason this milestone needs a
     * saga at all.
     *
     * The hold is real and lives in the Ledger's database. The limit check runs
     * against Payments' database and refuses. There is no transaction that spans
     * the two, so the hold cannot be rolled back — it has to be undone by a new,
     * forward command. That is compensation, and here is the whole path:
     * consume fails, the cap is released, the hold is voided, and the saga ends
     * COMPENSATED with the reason preserved.
     */
    @Test
    void aLimitRefusalAfterTheHoldCompensatesByReleasingTheCapAndVoidingTheHold() {
        limits.setCap(PAYER, 1_000L, "USD");

        expectAuthorize(withHold(82L, "ACTIVE"));
        ledger.expect(requestTo("http://localhost:8080/holds/82/void"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withHold(82L, "VOIDED"));

        PaymentView accepted = start("key-over-limit");
        // authorize, consume (fails -> COMPENSATING), void, settle.
        drive(4);

        PaymentView after = reload(accepted);
        assertThat(after.sagaStatus()).isEqualTo(PaymentSaga.COMPENSATED);
        assertThat(after.status()).isEqualTo(PaymentView.FAILED);
        assertThat(after.failureCode()).isEqualTo("PAYMENT_LIMIT_EXCEEDED");
        // No RELEASE_LIMIT: only steps that SUCCEEDED are compensated, and this
        // one refused before recording anything. Issuing a release here would be
        // undoing something that never happened -- harmless today, and exactly
        // the habit that produces a double-release the day the step gets a
        // partial-success path.
        assertThat(stepStatuses(after)).containsExactly(
                "AUTHORIZE=SUCCEEDED", "CONSUME_LIMIT=FAILED", "VOID=SUCCEEDED");
        assertThat(after.ledgerTransactionId()).isNull();
    }

    /**
     * Compensations run in reverse, so the cap is given back before the hold is
     * released. If they ran forwards there would be an instant where the cap was
     * free but the funds were still reserved, and a customer retrying straight
     * after a decline would pass the cap check only to fail on funds their own
     * abandoned hold was holding.
     */
    @Test
    void compensationsRunInReverseOrderOfTheStepsTheyUndo() {
        // A cap the payment fits under, so CONSUME_LIMIT succeeds and genuinely
        // has something to undo. The failure is moved to CAPTURE -- the only
        // point at which both earlier steps are outstanding at once.
        limits.setCap(PAYER, 10_000L, "USD");
        expectAuthorize(withHold(83L, "ACTIVE"));
        ledger.expect(requestTo("http://localhost:8080/holds/83/capture"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"HOLD_EXPIRED","message":"Hold 83 lapsed"}
                                """));
        ledger.expect(requestTo("http://localhost:8080/holds/83/void"))
                .andRespond(withHold(83L, "VOIDED"));

        PaymentView accepted = start("key-reverse");
        // authorize, consume, capture (fails -> COMPENSATING), release, void, settle.
        drive(6);

        PaymentView after = reload(accepted);
        assertThat(after.sagaStatus()).isEqualTo(PaymentSaga.COMPENSATED);

        List<String> compensations = after.steps().stream()
                .filter(step -> "COMPENSATION".equals(step.kind()))
                .map(PaymentView.StepView::step)
                .toList();
        assertThat(compensations).containsExactly("RELEASE_LIMIT", "VOID");
        // And the cap really is back.
        assertThat(limits.find(PAYER).orElseThrow().consumedMinor()).isZero();
    }

    /**
     * A cap that a payment fits under is consumed, and the payment proceeds. The
     * mirror of the refusal above -- without it, "the limit check works" could
     * be satisfied by a step that refuses everything.
     */
    @Test
    void aPaymentWithinTheCapConsumesItAndCarriesOn() {
        limits.setCap(PAYER, 10_000L, "USD");
        expectAuthorize(withHold(84L, "ACTIVE"));
        expectCapture(84L, withCapture(84L, 503L));

        PaymentView accepted = start("key-within-cap");
        drive(4);

        assertThat(reload(accepted).sagaStatus()).isEqualTo(PaymentSaga.COMPLETED);
        assertThat(limits.find(PAYER).orElseThrow().consumedMinor()).isEqualTo(AMOUNT);
        assertThat(limits.find(PAYER).orElseThrow().remainingMinor()).isEqualTo(7_500L);
    }

    /**
     * A voided hold that the Ledger reports as already not ACTIVE is reconciled
     * against the hold's real state rather than assumed. Expired counts as done:
     * the compensation's goal is that the funds are not reserved, and an expiry
     * reaches that goal by another route.
     */
    @Test
    void aCompensationSucceedsIfTheHoldHasAlreadyLapsedOnItsOwn() {
        limits.setCap(PAYER, 1_000L, "USD");
        expectAuthorize(withHold(85L, "ACTIVE"));
        ledger.expect(requestTo("http://localhost:8080/holds/85/void"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"HOLD_NOT_ACTIVE","message":"Hold 85 is EXPIRED"}
                                """));
        ledger.expect(requestTo("http://localhost:8080/holds/85"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withHold(85L, "EXPIRED"));

        PaymentView accepted = start("key-lapsed");
        drive(5);

        assertThat(reload(accepted).sagaStatus()).isEqualTo(PaymentSaga.COMPENSATED);
    }

    /**
     * The one state this system cannot resolve on its own, and does not pretend
     * to: the hold it wanted to release turns out to have been captured. Money
     * moved. That is escalated as COMPENSATION_FAILED rather than retried
     * forever or quietly reported as compensated.
     */
    @Test
    void aCompensationOverACapturedHoldIsEscalatedRatherThanHidden() {
        limits.setCap(PAYER, 1_000L, "USD");
        expectAuthorize(withHold(86L, "ACTIVE"));
        ledger.expect(requestTo("http://localhost:8080/holds/86/void"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"HOLD_NOT_ACTIVE","message":"Hold 86 is CAPTURED"}
                                """));
        ledger.expect(requestTo("http://localhost:8080/holds/86"))
                .andRespond(withHold(86L, "CAPTURED"));

        PaymentView accepted = start("key-captured");
        drive(5);

        PaymentView after = reload(accepted);
        assertThat(after.sagaStatus()).isEqualTo(PaymentSaga.FAILED);
        assertThat(after.failureCode()).isEqualTo("COMPENSATION_FAILED");
        assertThat(after.failureMessage()).contains("HOLD_ALREADY_CAPTURED");
    }

    /**
     * A compensation gets a much larger retry budget than a forward step, and
     * the asymmetry is deliberate.
     *
     * "Declined" is a real answer to a forward step, so giving up on one is a
     * decision the system may make. There is no equivalent for a compensation:
     * the alternative to releasing the payer's funds is leaving them reserved
     * behind a payment that already failed. Here the void is refused with a 5xx
     * more times than a forward step would tolerate, and the saga keeps trying.
     */
    @Test
    void aCompensationKeepsTryingLongAfterAForwardStepWouldHaveGivenUp() {
        limits.setCap(PAYER, 1_000L, "USD");
        expectAuthorize(withHold(87L, "ACTIVE"));
        // Six failures: one more than max-attempts, which would have ended a
        // forward step outright.
        ledger.expect(ExpectedCount.times(6), requestTo("http://localhost:8080/holds/87/void"))
                .andRespond(withServerError());

        PaymentView accepted = start("key-stubborn-compensation");
        drive(8);

        PaymentView after = reload(accepted);
        assertThat(after.sagaStatus()).isEqualTo(PaymentSaga.COMPENSATING);
        assertThat(after.failureCode()).isEqualTo("PAYMENT_LIMIT_EXCEEDED");

        // And it still finishes once the Ledger comes back.
        ledger.reset();
        ledger.expect(requestTo("http://localhost:8080/holds/87/void"))
                .andRespond(withHold(87L, "VOIDED"));
        drive(3);

        assertThat(reload(accepted).sagaStatus()).isEqualTo(PaymentSaga.COMPENSATED);
    }

    // ------------------------------------------------------------- utilities

    private PaymentView start(String key) {
        return payments.start(key, new PaymentRequest(PAYER, PAYEE, AMOUNT, "USD"));
    }

    private PaymentView reload(PaymentView view) {
        return payments.find(UUID.fromString(view.paymentId())).orElseThrow();
    }

    /**
     * Run the driver by hand, with a pause between ticks.
     *
     * The pause is not padding: a retry sets next_attempt_at in the future, and
     * the driver only claims sagas that are due. Back-to-back ticks in the same
     * millisecond would find nothing due and silently do nothing, so a test
     * about retries would pass or fail on how fast the machine is. The test
     * overrides shrink the backoff window to a few milliseconds precisely so
     * this wait can stay short and still be certain.
     */
    private void drive(int ticks) {
        for (int i = 0; i < ticks; i++) {
            driver.runOnce();
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private List<String> stepStatuses(PaymentView view) {
        return view.steps().stream().map(step -> step.step() + "=" + step.status()).toList();
    }

    private void expectAuthorize(org.springframework.test.web.client.ResponseCreator response) {
        ledger.expect(requestTo("http://localhost:8080/holds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.payerAccountId").value((int) PAYER))
                .andExpect(jsonPath("$.payeeAccountId").value((int) PAYEE))
                .andRespond(response);
    }

    private void expectCapture(long holdId, org.springframework.test.web.client.ResponseCreator response) {
        ledger.expect(requestTo("http://localhost:8080/holds/" + holdId + "/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amountMinor").value((int) AMOUNT))
                .andRespond(response);
    }

    private static org.springframework.test.web.client.ResponseCreator withHold(long holdId, String status) {
        return withSuccess("""
                {"holdId":%d,"accountId":%d,"payeeAccountId":%d,"amountMinor":%d,
                 "currency":"USD","status":"%s","expiresAt":"2030-01-01T00:00:00Z"}
                """.formatted(holdId, PAYER, PAYEE, AMOUNT, status), MediaType.APPLICATION_JSON);
    }

    private static org.springframework.test.web.client.ResponseCreator withCapture(long holdId, long txnId) {
        return withSuccess("""
                {"holdId":%d,"transactionId":%d,"capturedMinor":%d,"releasedMinor":0,
                 "currency":"USD","status":"CAPTURED"}
                """.formatted(holdId, txnId, AMOUNT), MediaType.APPLICATION_JSON);
    }
}
