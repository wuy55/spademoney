package com.spademoney.payments.payment;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
     * 202 Accepted, with a Location header that now points at something real.
     *
     * <h2>Why 202 and not 201</h2>
     * 201 Created promises the thing exists in its finished form. A payment does
     * not: it is a saga that has just been written down and has not run a step
     * yet. 202 says exactly what happened — the request was accepted, the
     * outcome is not yet known, here is where to look.
     *
     * <h2>The Location header, finally</h2>
     * Sessions 6 through 8 deliberately shipped a 201 with NO Location, because
     * the obvious {@code /payments/{id}} would have pointed at a 404 — Payments
     * stored no payments. (The Ledger shipped precisely that lie for two
     * sessions in M2 before anyone noticed.) The saga is that missing resource,
     * so the header arrives with the thing it names, and not a session earlier.
     */
    @PostMapping
    ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        // Missing header -> Spring's own 400 (required = true by default);
        // present-but-blank is not Spring's problem, so it is checked here. Both
        // answer 400 IDEMPOTENCY_KEY_REQUIRED, matching the Ledger's taxonomy
        // for the same fault.
        if (idempotencyKey.isBlank()) {
            throw new BlankIdempotencyKeyException();
        }

        PaymentView payment = payments.start(idempotencyKey, request);

        return ResponseEntity.accepted()
                .location(URI.create("/payments/" + payment.paymentId()))
                .body(new PaymentResponse(payment.paymentId(), payment.status()));
    }

    /**
     * The resource the Location header names, and the way a client learns how a
     * payment ended.
     *
     * No Idempotency-Key: reads change nothing, so there is nothing to make
     * idempotent.
     */
    @GetMapping("/{paymentId}")
    ResponseEntity<PaymentView> get(@PathVariable String paymentId) {
        UUID id;
        try {
            id = UUID.fromString(paymentId);
        } catch (IllegalArgumentException e) {
            // A malformed id is a 404, not a 400. The distinction a caller can
            // act on is "no such payment", and whether the id was the wrong
            // shape or merely unknown is not their problem.
            return ResponseEntity.notFound().build();
        }
        return payments.find(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
