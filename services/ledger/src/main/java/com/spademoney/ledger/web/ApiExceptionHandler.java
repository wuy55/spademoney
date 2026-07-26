package com.spademoney.ledger.web;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.spademoney.ledger.account.AccountNotFoundException;
import com.spademoney.ledger.hold.CaptureExceedsHoldException;
import com.spademoney.ledger.hold.HoldExpiredException;
import com.spademoney.ledger.hold.HoldNotActiveException;
import com.spademoney.ledger.hold.HoldNotFoundException;
import com.spademoney.ledger.idempotency.BlankIdempotencyKeyException;
import com.spademoney.ledger.idempotency.IdempotencyConflictException;
import com.spademoney.ledger.idempotency.IdempotencyKeyReusedException;
import com.spademoney.ledger.refund.RefundExceedsOriginalException;
import com.spademoney.ledger.refund.RefundTargetNotFoundException;
import com.spademoney.ledger.refund.UnrefundableTransactionException;
import com.spademoney.ledger.service.AccountNotFoundInLedgerException;
import com.spademoney.ledger.service.CurrencyMismatchException;
import com.spademoney.ledger.service.InsufficientFundsException;
import com.spademoney.ledger.transfer.TransferNotFoundException;

/**
 * The error taxonomy. Every money-mutating failure carries its own code, so a
 * client can tell "you are short" from "your request is malformed" from "that
 * hold is already gone" without parsing a message.
 *
 * 400 IDEMPOTENCY_KEY_REQUIRED  missing or blank Idempotency-Key
 * 400 VALIDATION_FAILED         body failed @Valid
 * 404 NOT_FOUND                 unknown account / transfer / hold
 * 409 IDEMPOTENCY_IN_PROGRESS   original request for this key still in flight (+ Retry-After)
 * 422 IDEMPOTENCY_KEY_REUSED    key reused with a different request fingerprint
 * 422 INSUFFICIENT_FUNDS        available balance below the requested amount
 * 422 CURRENCY_MISMATCH         account currency differs from the request
 * 422 HOLD_NOT_ACTIVE           hold already captured or voided
 * 422 HOLD_EXPIRED              authorization window lapsed before capture
 * 422 CAPTURE_EXCEEDS_HOLD      capture larger than the authorized amount
 * 422 REFUND_EXCEEDS_CAPTURED   refund would take back more than was captured
 * 422 UNREFUNDABLE              target is itself a refund, or not a simple posting
 * 422 UNPROCESSABLE             anything else the domain rejects
 *
 * Every 4xx above tells the client whether retrying can ever help: 409 says
 * "shortly"; every 422 says "this request as written will never succeed".
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    @ExceptionHandler(BlankIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> onBlankKey(BlankIdempotencyKeyException ex) {
        return body(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> onConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(Map.of("code", "IDEMPOTENCY_IN_PROGRESS", "message", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<Map<String, String>> onKeyReused(IdempotencyKeyReusedException ex) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> onInsufficientFunds(InsufficientFundsException ex) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_FUNDS", ex.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<Map<String, String>> onCurrencyMismatch(CurrencyMismatchException ex) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "CURRENCY_MISMATCH", ex.getMessage());
    }

    @ExceptionHandler(HoldNotActiveException.class)
    public ResponseEntity<Map<String, String>> onHoldNotActive(HoldNotActiveException ex) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "HOLD_NOT_ACTIVE", ex.getMessage());
    }

    @ExceptionHandler(HoldExpiredException.class)
    public ResponseEntity<Map<String, String>> onHoldExpired(HoldExpiredException ex) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "HOLD_EXPIRED", ex.getMessage());
    }

    @ExceptionHandler(CaptureExceedsHoldException.class)
    public ResponseEntity<Map<String, String>> onCaptureTooLarge(CaptureExceedsHoldException ex) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "CAPTURE_EXCEEDS_HOLD", ex.getMessage());
    }

    @ExceptionHandler(RefundExceedsOriginalException.class)
    public ResponseEntity<Map<String, String>> onRefundTooLarge(RefundExceedsOriginalException ex) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "REFUND_EXCEEDS_CAPTURED", ex.getMessage());
    }

    @ExceptionHandler(UnrefundableTransactionException.class)
    public ResponseEntity<Map<String, String>> onUnrefundable(UnrefundableTransactionException ex) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "UNREFUNDABLE", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onInvalid(IllegalArgumentException ex) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "UNPROCESSABLE", ex.getMessage());
    }

    @ExceptionHandler({ TransferNotFoundException.class, AccountNotFoundException.class,
            HoldNotFoundException.class, AccountNotFoundInLedgerException.class,
            RefundTargetNotFoundException.class })
    public ResponseEntity<Map<String, String>> onNotFound(RuntimeException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }
}
