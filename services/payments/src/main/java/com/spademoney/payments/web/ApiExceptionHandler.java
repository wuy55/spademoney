package com.spademoney.payments.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.spademoney.payments.ledger.LedgerRejectedException;
import com.spademoney.payments.ledger.LedgerTimeoutException;
import com.spademoney.payments.ledger.LedgerUnavailableException;

/**
 * Payments' error taxonomy. Same {@code {code, message}} envelope as the
 * Ledger's, so a client parses one shape across both services.
 *
 * <pre>
 * 400 IDEMPOTENCY_KEY_REQUIRED  missing or blank Idempotency-Key
 * 400 VALIDATION_FAILED         body failed @Valid
 * 4xx (from the Ledger)         status and code passed through, with source: "ledger"
 * 502 LEDGER_UNAVAILABLE        Ledger unreachable or 5xx — the transfer did not happen
 * 504 LEDGER_TIMEOUT            no answer in time — whether it happened is unknown
 * </pre>
 *
 * <h2>Why 502 and 504 are different codes</h2>
 * They answer different questions. 502 means the request was not processed, so
 * resubmitting is safe. 504 means Payments does not know, so resubmitting may
 * double-charge. Flattening both to "the ledger broke" would erase the only
 * distinction a caller can act on — and it is the distinction the rest of M3
 * exists to make actionable.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<Map<String, String>> body(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    @ExceptionHandler(BlankIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> onBlankKey(BlankIdempotencyKeyException ex) {
        return body(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", ex.getMessage());
    }

    /**
     * Spring answers a missing required header with a bare 400 and no body, so
     * the one fault a client is most likely to hit would be the one error in
     * this API that does not carry a machine-readable code. Handled here to keep
     * the envelope uniform, and to give the same code as a blank key: from the
     * caller's side they are the same mistake.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> onMissingHeader(MissingRequestHeaderException ex) {
        return body(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> onInvalidBody(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("request body failed validation");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    /**
     * The Ledger said no. Its status and code are reproduced verbatim — an
     * INSUFFICIENT_FUNDS is INSUFFICIENT_FUNDS wherever the caller hears it, and
     * remapping it into a Payments-flavoured synonym would create two names for
     * one fact.
     *
     * {@code source} is added so a caller can tell a rejection Payments made
     * from one it relayed. That matters the moment Payments starts rejecting
     * things itself (Session 9's limit check).
     */
    @ExceptionHandler(LedgerRejectedException.class)
    public ResponseEntity<Map<String, String>> onLedgerRejection(LedgerRejectedException ex) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("code", ex.error().code());
        payload.put("message", ex.error().message());
        payload.put("source", "ledger");
        return ResponseEntity.status(ex.status()).body(payload);
    }

    @ExceptionHandler(LedgerUnavailableException.class)
    public ResponseEntity<Map<String, String>> onLedgerUnavailable(LedgerUnavailableException ex) {
        return body(HttpStatus.BAD_GATEWAY, "LEDGER_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(LedgerTimeoutException.class)
    public ResponseEntity<Map<String, String>> onLedgerTimeout(LedgerTimeoutException ex) {
        return body(HttpStatus.GATEWAY_TIMEOUT, "LEDGER_TIMEOUT", ex.getMessage());
    }
}
