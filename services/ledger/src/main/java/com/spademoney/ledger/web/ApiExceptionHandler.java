package com.spademoney.ledger.web;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.spademoney.ledger.idempotency.BlankIdempotencyKeyException;
import com.spademoney.ledger.idempotency.IdempotencyConflictException;
import com.spademoney.ledger.idempotency.IdempotencyKeyReusedException;
import com.spademoney.ledger.transfer.TransferNotFoundException;
import com.spademoney.ledger.account.AccountNotFoundException;

/**
 * Error taxonomy:
 * 409 IDEMPOTENCY_IN_PROGRESS — original request for this key still running (+ Retry-After)
 * 422 IDEMPOTENCY_KEY_REUSED — key reused with a different request fingerprint
 * 422 UNPROCESSABLE — insufficient funds, unknown account, currency mismatch
 * 400 — missing or blank Idempotency-Key header
 *
 * LedgerTransactionService signals insufficient-funds, account-not-found and
 * currency-mismatch all as IllegalArgumentException, so they collapse into one
 * 422 code here; splitting them into distinct typed exceptions is future work.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BlankIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> onBlankKey(BlankIdempotencyKeyException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "IDEMPOTENCY_KEY_REQUIRED", "message", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> onConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(Map.of("code", "IDEMPOTENCY_IN_PROGRESS", "message", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<Map<String, String>> onKeyReused(IdempotencyKeyReusedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("code", "IDEMPOTENCY_KEY_REUSED", "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onInvalid(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("code", "UNPROCESSABLE", "message", ex.getMessage()));
    }

    @ExceptionHandler({ TransferNotFoundException.class, AccountNotFoundException.class })
    public ResponseEntity<Map<String, String>> onNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "NOT_FOUND", "message", ex.getMessage()));
    }
}