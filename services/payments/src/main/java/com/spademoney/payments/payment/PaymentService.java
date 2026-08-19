package com.spademoney.payments.payment;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.spademoney.payments.ledger.LedgerClient;
import com.spademoney.payments.ledger.LedgerIdempotencyKeys;
import com.spademoney.payments.ledger.LedgerTransferCommand;
import com.spademoney.payments.ledger.LedgerTransferResult;

/**
 * One payment, one Ledger call. That is the entire service today.
 *
 * It is not transactional and touches no table. Payments' database exists but
 * holds only {@code payment_limits}, which nothing reads until Session 9 — so
 * there is no local state to keep in step with the remote call, and therefore
 * nothing here that a distributed transaction would even be asked to protect.
 * That changes in Session 7, when the domain write and its outbox row have to
 * land in one local transaction.
 */
@Service
public class PaymentService {

    private final LedgerClient ledger;

    PaymentService(LedgerClient ledger) {
        this.ledger = ledger;
    }

    /**
     * @param callerKey the client's Idempotency-Key. Required, validated, and
     *                  then — for now — unused: Payments has no idempotency
     *                  store of its own until the saga arrives in Session 9,
     *                  and it is deliberately never forwarded to the Ledger
     *                  ({@link LedgerIdempotencyKeys}). Demanding it from day
     *                  one keeps the published contract stable across that
     *                  change, so clients written today keep working.
     */
    public PaymentResponse pay(String callerKey, PaymentRequest request) {
        String paymentId = UUID.randomUUID().toString();

        LedgerTransferCommand command = LedgerTransferCommand.from(request);
        LedgerTransferResult result = ledger.transfer(command, LedgerIdempotencyKeys.forTransfer(paymentId));

        return new PaymentResponse(paymentId, result.status(), result.transactionId());
    }
}
