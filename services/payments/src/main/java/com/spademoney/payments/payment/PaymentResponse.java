package com.spademoney.payments.payment;

/**
 * The 202 body of POST /payments.
 *
 * <h2>Why this is not the result of the payment</h2>
 * It cannot be. The payment is three steps across two services, and the first
 * of them has not necessarily run when this is written — deliberately, because
 * the driver is the only path a saga advances on and recovery therefore shares
 * its code with the happy case.
 *
 * So the endpoint answers 202 Accepted with an identity and a place to look,
 * rather than blocking a client socket for the duration of a distributed
 * workflow and still having to answer "unknown" if anything times out. The
 * caller polls {@code GET /payments/{paymentId}} — the resource the Location
 * header names, which now genuinely exists.
 */
public record PaymentResponse(String paymentId, String status) {
}
