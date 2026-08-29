package com.spademoney.payments.limit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * Administering spending caps.
 *
 * PUT rather than POST, and no Idempotency-Key: setting a cap is naturally
 * idempotent — the same request applied twice leaves the same state — so
 * demanding a key would be ceremony without a purpose. The money-mutating
 * endpoints are the ones that need one, and they have it.
 *
 * This is an operator surface, not a customer one. In a real deployment it would
 * sit behind authentication; there is none here, and pretending otherwise with a
 * token nobody checks would be worse than saying so.
 */
@RestController
@RequestMapping("/limits")
class PaymentLimitController {

    private final PaymentLimitService limits;

    PaymentLimitController(PaymentLimitService limits) {
        this.limits = limits;
    }

    @PutMapping("/{accountId}")
    ResponseEntity<PaymentLimitView> set(@PathVariable long accountId,
            @Valid @RequestBody PaymentLimitRequest request) {
        limits.setCap(accountId, request.capMinor(), request.currency());
        return ResponseEntity.ok(limits.find(accountId).orElseThrow());
    }

    @GetMapping("/{accountId}")
    ResponseEntity<PaymentLimitView> get(@PathVariable long accountId) {
        return limits.find(accountId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
