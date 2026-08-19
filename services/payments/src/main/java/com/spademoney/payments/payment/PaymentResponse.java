package com.spademoney.payments.payment;

/**
 * The result of a payment.
 *
 * {@code ledgerTransactionId} is exposed rather than hidden: it is the handle a
 * reviewer (or a curl in the README) uses to check the Ledger's own
 * GET /transfers/{id} and see both sides of the posting. Hiding it would make
 * the two-service claim unverifiable from the outside.
 *
 * This shape changes in Session 9, when POST /payments becomes asynchronous and
 * answers 202 with a saga id instead of a completed transaction. That pending
 * change is why no OpenAPI document is published for this endpoint yet.
 */
public record PaymentResponse(String paymentId, String status, Long ledgerTransactionId) {
}
