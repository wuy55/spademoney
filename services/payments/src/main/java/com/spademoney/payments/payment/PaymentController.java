package com.spademoney.payments.payment;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.spademoney.payments.web.BlankIdempotencyKeyException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /**
     * 201 with a body and deliberately <em>no</em> Location header.
     *
     * The obvious thing is to return {@code Location: /payments/{paymentId}},
     * and it would be wrong: there is no GET /payments/{id}, because Payments
     * stores no payments yet. The Ledger shipped exactly that header pointing at
     * a 404 for two sessions before anyone noticed. A Location that does not
     * resolve is worse than none — it is a documented lie.
     *
     * The header arrives with the resource, in Session 9, when a persisted saga
     * gives it something to point at.
     */
    // Missing header -> Spring returns 400 automatically (required = true by default);
    // present-but-blank is not Spring's problem, so it is checked here. Both answer
    // 400 IDEMPOTENCY_KEY_REQUIRED, matching the Ledger's taxonomy for the same fault.
    @PostMapping
    ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        if (idempotencyKey.isBlank()) {
            throw new BlankIdempotencyKeyException();
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payments.pay(idempotencyKey, request));
    }
}
