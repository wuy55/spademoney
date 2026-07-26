package com.spademoney.ledger.hold;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.spademoney.ledger.idempotency.IdempotencyService;
import com.spademoney.ledger.idempotency.IdempotencyService.Outcome;

import jakarta.validation.Valid;

/**
 * The controller supplies the ACTION to the idempotency handler rather than the
 * handler knowing about holds. Keeps one idempotency implementation for every
 * money-mutating endpoint without it accumulating a dependency on each service.
 */
@RestController
@RequestMapping("/holds")
public class HoldController {

    private final IdempotencyService idempotency;
    private final HoldService holds;
    private final HoldQueryService queries;

    HoldController(IdempotencyService idempotency, HoldService holds, HoldQueryService queries) {
        this.idempotency = idempotency;
        this.holds = holds;
        this.queries = queries;
    }

    /** The resource POST /holds advertises in its Location header. */
    @GetMapping("/{id}")
    HoldResponse get(@PathVariable long id) {
        return queries.findHold(id).orElseThrow(() -> new HoldNotFoundException(id));
    }

    @PostMapping
    ResponseEntity<HoldResponse> authorize(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AuthorizeRequest request) {

        HoldResponse response = idempotency.execute(
                IdempotencyService.OP_AUTHORIZE, idempotencyKey, request,
                HoldResponse.class, 201,
                () -> Outcome.of(holds.authorize(request)));

        return ResponseEntity.created(URI.create("/holds/" + response.holdId())).body(response);
    }

    /**
     * Capture creates a ledger transaction, so its Outcome carries the
     * transaction id -- unlike authorize and void, which post nothing and leave
     * idempotency_keys.transaction_id NULL.
     */
    @PostMapping("/{id}/capture")
    ResponseEntity<CaptureResponse> capture(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable long id,
            @Valid @RequestBody CaptureAmount body) {

        CaptureResponse response = idempotency.execute(
                IdempotencyService.OP_CAPTURE, idempotencyKey,
                new CaptureRequest(id, body.amountMinor()),
                CaptureResponse.class, 200,
                () -> {
                    CaptureResponse captured = holds.capture(id, body.amountMinor());
                    return new Outcome<>(captured, captured.transactionId());
                });

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/void")
    ResponseEntity<HoldResponse> voidHold(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable long id) {

        HoldResponse response = idempotency.execute(
                IdempotencyService.OP_VOID, idempotencyKey, new VoidHoldRequest(id),
                HoldResponse.class, 200,
                () -> Outcome.of(holds.voidHold(id)));

        return ResponseEntity.ok(response);
    }
}
