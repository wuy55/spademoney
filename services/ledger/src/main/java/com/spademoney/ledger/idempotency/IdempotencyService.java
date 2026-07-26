package com.spademoney.ledger.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spademoney.ledger.money.Money;
import com.spademoney.ledger.service.LedgerTransactionService;
import com.spademoney.ledger.transfer.TransferRequest;
import com.spademoney.ledger.transfer.TransferResponse;

/**
 * The idempotency contract, generic over the operation.
 *
 * The claim, the state change and the COMPLETED record all commit in ONE
 * transaction. Consequences:
 * - A concurrent duplicate does not see IN_PROGRESS (READ COMMITTED hides the
 *   uncommitted row). It blocks on the unique index instead, then re-reads and
 *   replays. The unique index -- not the status flag -- serializes duplicates.
 * - Nothing can strand an IN_PROGRESS row: a crash rolls the whole thing back.
 *   The 409 path defends against out-of-band stuck rows, not normal concurrency.
 *
 * `operation` is a FIXED string per endpoint and never contains a resource id.
 * Ids belong in the fingerprint, so one key reused against a different hold
 * yields 422 rather than quietly getting its own key scope.
 *
 * The action returns an Outcome so operations that create no transaction
 * (authorize, void) can leave transaction_id NULL.
 *
 * The action MUST NOT open its own transaction. If it does, the atomicity
 * argument behind ADR-005 and ADR-010 collapses.
 */
@Service
public class IdempotencyService {

    private static final String SELECT_SQL = """
            SELECT request_fingerprint, status, response_status, response_body, transaction_id
              FROM idempotency_keys
             WHERE endpoint = ? AND idempotency_key = ?
            """;

    private static final String CLAIM_SQL = """
            INSERT INTO idempotency_keys
                   (endpoint, idempotency_key, request_fingerprint, status, created_at)
            VALUES (?, ?, ?, 'IN_PROGRESS', now())
            ON CONFLICT (endpoint, idempotency_key) DO NOTHING
            """;

    private static final String COMPLETE_SQL = """
            UPDATE idempotency_keys
               SET status = 'COMPLETED',
                   response_status = ?,
                   response_body   = ?,
                   transaction_id  = ?,
                   completed_at    = now()
             WHERE endpoint = ? AND idempotency_key = ?
            """;

    public static final String OP_TRANSFER = "/transfers";
    public static final String OP_AUTHORIZE = "/holds/authorize";
    public static final String OP_VOID = "/holds/void";
    public static final String OP_CAPTURE = "/holds/capture";
    public static final String OP_REFUND = "/refunds";

    private static final int RETRY_AFTER_SECONDS = 1;

    private final JdbcClient jdbcClient;
    private final LedgerTransactionService ledger;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbcClient, LedgerTransactionService ledger, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
    }

    /**
     * What an operation produces: the wire response, plus the ledger transaction
     * it created if it created one. Authorize and void create none.
     */
    public record Outcome<T>(T response, Long transactionId) {
        public static <T> Outcome<T> of(T response) {
            return new Outcome<>(response, null);
        }
    }

    private record IdempotencyRecord(
            String requestFingerprint,
            String status,
            Integer responseStatus,
            String responseBody,
            Long transactionId) {
    }

    @Transactional
    public <T> T execute(String operation,
            String idempotencyKey,
            IdempotentRequest request,
            Class<T> responseType,
            int successStatus,
            Supplier<Outcome<T>> action) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BlankIdempotencyKeyException("Idempotency-Key header can not be blank");
        }

        final String fingerprint = fingerprint(request);

        Optional<IdempotencyRecord> existing = findRecord(operation, idempotencyKey);
        if (existing.isPresent()) {
            return handleExisting(existing.get(), fingerprint, responseType);
        }

        // Claim. 1 = we inserted, 0 = a conflicting row won and we blocked on the
        // unique index until it committed (had it rolled back there would be no
        // conflict and we would have inserted). Because claim and COMPLETED
        // commit together, the row we then re-read is COMPLETED, never IN_PROGRESS.
        int claimed = jdbcClient.sql(CLAIM_SQL)
                .params(operation, idempotencyKey, fingerprint)
                .update();

        if (claimed == 0) {
            IdempotencyRecord winner = findRecord(operation, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Insert conflicted but no row is visible for key " + idempotencyKey));
            return handleExisting(winner, fingerprint, responseType);
        }

        // We own the key. The state change happens now, inside this transaction.
        // A thrown exception rolls back the claim with everything else, freeing
        // the key: it names an operation that succeeded, not an attempt that failed.
        Outcome<T> outcome = action.get();

        int rowsUpdated = jdbcClient.sql(COMPLETE_SQL)
                .params(successStatus, serialize(outcome.response()), outcome.transactionId(),
                        operation, idempotencyKey)
                .update();
        if (rowsUpdated != 1) {
            throw new IllegalStateException(
                    "Expected to complete exactly 1 idempotency row, updated " + rowsUpdated);
        }

        return outcome.response();
    }

    /**
     * The transfer path, named so callers do not have to assemble the action.
     *
     * @Transactional is REQUIRED here and is not redundant with the annotation
     * on execute(). This is a self-invocation: `execute(...)` is called on
     * `this`, not on the Spring proxy, so the proxy's transaction advice never
     * runs for it. Without this annotation the whole transfer path executes
     * with autocommit -- the claim commits on its own, making IN_PROGRESS
     * visible (spurious 409s under concurrency) and leaving the key claimed
     * after a failed transfer. Both are the four-case contract breaking.
     */
    @Transactional
    public TransferResponse executeTransfer(String idempotencyKey, TransferRequest request) {
        return execute(OP_TRANSFER, idempotencyKey, request, TransferResponse.class, 201, () -> {
            Currency currency = Currency.getInstance(request.currency());
            Money amount = Money.of(request.amountMinor(), currency);
            Long transactionId = ledger.transfer(request.fromAccountId(), request.toAccountId(), amount);
            return new Outcome<>(new TransferResponse(transactionId, "POSTED"), transactionId);
        });
    }

    private Optional<IdempotencyRecord> findRecord(String operation, String idempotencyKey) {
        return jdbcClient.sql(SELECT_SQL)
                .params(operation, idempotencyKey)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("request_fingerprint"),
                        rs.getString("status"),
                        // getObject(..., Class) preserves SQL NULL as Java null.
                        // getInt/getLong would silently return 0 on an IN_PROGRESS row.
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body"),
                        rs.getObject("transaction_id", Long.class)))
                .optional();
    }

    private <T> T handleExisting(IdempotencyRecord record, String fingerprint, Class<T> responseType) {
        // Fingerprint before status: a key reused for a DIFFERENT request is a
        // client bug worth reporting even if the original is still in flight.
        if (!record.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException("Idempotency key reused with a different request");
        }
        if ("IN_PROGRESS".equals(record.status())) {
            throw new IdempotencyConflictException("Request is in flight", RETRY_AFTER_SECONDS);
        }
        return deserialize(record.responseBody(), responseType);
    }

    /**
     * SHA-256 of the request's canonical form. Same logical request => same
     * fingerprint; any differing field => different fingerprint.
     */
    static String fingerprint(IdempotentRequest request) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(request.canonicalForm().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize response", e);
        }
    }

    private <T> T deserialize(String json, Class<T> responseType) {
        try {
            return objectMapper.readValue(json, responseType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize response", e);
        }
    }
}
