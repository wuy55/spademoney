package com.spademoney.payments.ledger;

import com.spademoney.payments.payment.PaymentRequest;

/**
 * The exact body sent to the Ledger's POST /transfers.
 *
 * Note the field names differ from {@link PaymentRequest}'s: Payments speaks
 * payer/payee, the Ledger speaks from/to. That is not an oversight. The two
 * vocabularies are allowed to drift because the translation happens here, in
 * one visible place, instead of being disguised by a shared DTO.
 *
 * <h2>Why the mapping lives in a static factory</h2>
 * The Ledger's idempotency contract fingerprints the request body: replaying a
 * key with a *different* body is 422 IDEMPOTENCY_KEY_REUSED, not a replay. From
 * Session 9 the saga must therefore persist the command it sent alongside the
 * step, and resend those exact bytes on retry — recomputing the body from saga
 * state that has since moved on would hash differently and wedge the saga
 * permanently rather than failing loudly.
 *
 * Keeping construction to this one factory means that change is a matter of
 * storing what {@code from} returns, not of hunting down every place a body
 * gets assembled.
 */
public record LedgerTransferCommand(
        long fromAccountId,
        long toAccountId,
        long amountMinor,
        String currency) {

    /**
     * The single place a Ledger transfer body is built. Session 9 persists the
     * result of this call; nothing else should construct the command.
     */
    public static LedgerTransferCommand from(PaymentRequest request) {
        return new LedgerTransferCommand(
                request.payerAccountId(),
                request.payeeAccountId(),
                request.amountMinor(),
                request.currency());
    }
}
