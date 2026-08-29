package com.spademoney.payments.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.spademoney.payments.saga.PaymentSaga;
import com.spademoney.payments.saga.SagaRepository;
import com.spademoney.payments.saga.SagaStepRow;
import com.spademoney.payments.web.IdempotencyKeyReusedException;

/**
 * Starting a payment, and answering questions about one.
 *
 * <h2>Starting a payment writes a row and stops</h2>
 * No Ledger call happens here. The saga is committed and the driver picks it up
 * — which means an accepted payment survives this process dying one instruction
 * later, and means there is exactly one code path that advances a payment
 * whether or not anything has crashed.
 *
 * <h2>The four-case idempotency contract, on Payments' own scope</h2>
 * The same contract the Ledger implements, for the same reasons:
 *
 * <ol>
 *   <li>new key -> a new saga is created;</li>
 *   <li>known key, same request -> the existing saga is returned, no second
 *       payment;</li>
 *   <li>known key, different request -> 422, because that is a client bug and
 *       silently treating it as a replay would hide a mixed-up retry;</li>
 *   <li>concurrent duplicates -> the UNIQUE index picks a winner and the loser
 *       reads the winner's saga.</li>
 * </ol>
 *
 * Case 3 is checked before anything else, so a genuinely wrong reuse is
 * reported even while the original is still running.
 *
 * There is no IN_PROGRESS conflict case here, unlike the Ledger's. It does not
 * arise: the Ledger's version exists because its operations complete inside one
 * request, so a duplicate can genuinely find one in flight. A saga is
 * <em>always</em> in flight when it is created — asynchrony is the published
 * contract — so a replay simply returns the current state.
 */
@Service
public class PaymentService {

    private final SagaRepository sagas;

    PaymentService(SagaRepository sagas) {
        this.sagas = sagas;
    }

    public PaymentView start(String callerKey, PaymentRequest request) {
        String fingerprint = fingerprint(request);

        UUID candidateId = UUID.randomUUID();
        boolean created = sagas.tryCreate(candidateId, callerKey, fingerprint,
                request.payerAccountId(), request.payeeAccountId(),
                request.amountMinor(), request.currency());

        if (created) {
            return view(sagas.findById(candidateId).orElseThrow(), List.of());
        }

        // Somebody else owns this key: an earlier request, or a concurrent
        // duplicate that won the unique index. Either way the answer is theirs.
        PaymentSaga existing = sagas.findByIdempotencyKey(callerKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Insert conflicted but no saga is visible for key " + callerKey));

        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(
                    "Idempotency key reused with a different payment request");
        }
        return find(existing.id()).orElseThrow();
    }

    public Optional<PaymentView> find(UUID paymentId) {
        return sagas.findById(paymentId)
                .map(saga -> view(saga, sagas.findSteps(saga.id())));
    }

    private static PaymentView view(PaymentSaga saga, List<SagaStepRow> steps) {
        return new PaymentView(
                saga.id().toString(),
                externalStatus(saga.status()),
                saga.status(),
                saga.payerAccountId(),
                saga.payeeAccountId(),
                saga.amountMinor(),
                saga.currency(),
                saga.holdId(),
                saga.ledgerTransactionId(),
                saga.failureCode(),
                saga.failureMessage(),
                steps.stream()
                        .map(step -> new PaymentView.StepView(step.step(), step.kind(),
                                step.status(), step.attempts(), step.lastError()))
                        .toList());
    }

    /**
     * A compensating saga reports PENDING, not FAILED.
     *
     * It is going to fail, and saying so early would be more informative — and
     * wrong. Until the compensation has run, the payer's funds are still held.
     * Telling a customer "declined" while their money is still reserved invites
     * them to try again immediately against a balance that has not been given
     * back yet. The honest answer is "not finished".
     */
    private static String externalStatus(String sagaStatus) {
        return switch (sagaStatus) {
            case PaymentSaga.COMPLETED -> PaymentView.SUCCEEDED;
            case PaymentSaga.FAILED, PaymentSaga.COMPENSATED -> PaymentView.FAILED;
            default -> PaymentView.PENDING;
        };
    }

    /**
     * SHA-256 of the request's canonical form. Same shape as the Ledger's
     * fingerprint, and written out here rather than shared, because sharing it
     * would mean a module both services import.
     */
    static String fingerprint(PaymentRequest request) {
        String canonical = "payment|%d|%d|%d|%s".formatted(
                request.payerAccountId(), request.payeeAccountId(),
                request.amountMinor(), request.currency());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
