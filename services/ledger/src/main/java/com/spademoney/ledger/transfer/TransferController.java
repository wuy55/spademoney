package com.spademoney.ledger.transfer;

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
import com.spademoney.ledger.query.LedgerQueryService;

import jakarta.validation.Valid;

/**
 * The controller holds no idempotency logic — it parses the request and
 * delegates. Keeping the contract in one service, rather than spread across a
 * filter, interceptor and controller, keeps it in one place.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final IdempotencyService idempotencyService;
    private final LedgerQueryService queries;

    TransferController(IdempotencyService idempotencyService, LedgerQueryService queries) {
        this.idempotencyService = idempotencyService;
        this.queries = queries;
    }

    // Missing header -> Spring returns 400 automatically (required = true by default).
    @PostMapping
    ResponseEntity<TransferResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey, // required=true by default
            @Valid @RequestBody TransferRequest request) {

        TransferResponse response = idempotencyService.executeTransfer(idempotencyKey, request);
        return ResponseEntity
                .created(URI.create("/transfers/" + response.transactionId()))
                .body(response);
    }

    @GetMapping("/{id}")
    ResponseEntity<TransferView> get(@PathVariable long id) {
        return queries.findTransfer(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new TransferNotFoundException(id));
    }
}