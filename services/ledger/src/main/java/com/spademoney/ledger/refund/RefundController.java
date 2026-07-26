package com.spademoney.ledger.refund;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.spademoney.ledger.idempotency.IdempotencyService;
import com.spademoney.ledger.idempotency.IdempotencyService.Outcome;

import jakarta.validation.Valid;

/**
 * A refund is its own resource, not a sub-resource of the transfer it reverses:
 * one transaction can be refunded several times, and each refund is a posting
 * in its own right with its own id. The Location header points at the refund's
 * own transaction, readable through GET /transfers/{id} like any other.
 */
@RestController
@RequestMapping("/refunds")
public class RefundController {

    private final IdempotencyService idempotency;
    private final RefundService refunds;

    RefundController(IdempotencyService idempotency, RefundService refunds) {
        this.idempotency = idempotency;
        this.refunds = refunds;
    }

    @PostMapping
    ResponseEntity<RefundResponse> refund(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RefundRequest request) {

        RefundResponse response = idempotency.execute(
                IdempotencyService.OP_REFUND, idempotencyKey, request,
                RefundResponse.class, 201,
                () -> {
                    RefundResponse refunded = refunds.refund(request);
                    return new Outcome<>(refunded, refunded.refundTransactionId());
                });

        return ResponseEntity
                .created(URI.create("/transfers/" + response.refundTransactionId()))
                .body(response);
    }
}
