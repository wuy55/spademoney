package com.spademoney.ledger.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Currency;

import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spademoney.ledger.service.LedgerTransactionService;
import com.spademoney.ledger.transfer.TransferRequest;
import com.spademoney.ledger.transfer.TransferResponse;
import com.spademoney.ledger.money.Money;

/**
 * The idempotency contract. The claim, the money movement and the COMPLETED
 * record all commit in one transaction. Consequences:
 * - A concurrent duplicate does not see IN_PROGRESS (READ COMMITTED hides the
 *   uncommitted row). It blocks on the unique index instead, then gets a
 *   duplicate-key error, re-reads, and replays. The unique index — not the
 *   status flag — is what serializes concurrent duplicates.
 * - Nothing can strand an IN_PROGRESS row: a crash rolls the whole thing back.
 *   The 409 path exists as a defence for out-of-band stuck rows, not as the
 *   normal concurrency path.
 *
 * The alternative — commit the claim first, then money and COMPLETED in a
 * second transaction — makes IN_PROGRESS visible so 409 fires under real
 * concurrency, at the cost of stranded rows needing a TTL reaper.
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

    /**
     * Keys are scoped per endpoint: the same key on /transfers and /refunds must
     * not collide.
     */
    private static final String ENDPOINT = "/transfers";

    private static final int RETRY_AFTER_SECONDS = 1;

    private final JdbcClient jdbcClient;
    private final LedgerTransactionService ledger;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcClient jdbcClient, LedgerTransactionService ledger, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
    }

    /** The stored idempotency row, as far as this handler cares about it. */
    private record IdempotencyRecord(
            String requestFingerprint,
            String status,
            Integer responseStatus,
            String responseBody,
            Long transactionId) {
    }

    @Transactional
    public TransferResponse executeTransfer(String idempotencyKey, TransferRequest request) {

        if (idempotencyKey.isBlank()) {
            throw new BlankIdempotencyKeyException("Idempotency-Key header can not be blank");
        }

        final String fingerprint = fingerprint(request);

        Optional<IdempotencyRecord> existing = findRecord(idempotencyKey);

        // If present, branch before touching any money. Fingerprint is checked
        // before status (see handleExisting):
        // - fingerprint differs        -> 422 IdempotencyKeyReusedException
        // - status IN_PROGRESS         -> 409 IdempotencyConflictException
        // - COMPLETED + fingerprint ok -> replay the stored response
        if (existing.isPresent()) {
            return handleExisting(existing.get(), fingerprint);
        }

        // Claim the key. Returns 1 if we inserted, 0 if a conflicting row won.
        // 0 means we blocked on the winner's uncommitted row until it resolved:
        // had the winner rolled back there would be no conflict and we'd have
        // inserted, so 0 implies the winner committed. Because the claim and the
        // COMPLETED write commit together, the row we re-read is COMPLETED, never
        // IN_PROGRESS.
        int claimed = jdbcClient.sql(CLAIM_SQL)
                .params(ENDPOINT, idempotencyKey, fingerprint)
                .update();

        if (claimed == 0) {
            IdempotencyRecord winner = findRecord(idempotencyKey).orElseThrow(() -> new IllegalStateException(
                    "Insert conflicted but no row is visible for key " + idempotencyKey));
            return handleExisting(winner, fingerprint);
        }

        // We own the key. Money moves now. IllegalArgumentException (insufficient
        // funds, unknown account, currency mismatch) is allowed to propagate: the
        // whole transaction rolls back, taking the claim with it, so the key is
        // freed and a retry is treated as new. A key names an operation that
        // succeeded, not an attempt that failed.
        Currency currency = Currency.getInstance(request.currency());
        Money amount = Money.of(request.amountMinor(), currency);
        Long transactionId = ledger.transfer(request.fromAccountId(), request.toAccountId(), amount);

        TransferResponse response = new TransferResponse(transactionId, "POSTED");

        // Mark the row COMPLETED and store the response for replay.
        int rowsUpdated = jdbcClient.sql(COMPLETE_SQL)
                .params(201, serialize(response), transactionId, ENDPOINT, idempotencyKey)
                .update();
        if (rowsUpdated != 1) {
            throw new RuntimeException("Expected to complete exactly 1 idempotency row, updated " + rowsUpdated);
        }

        return response;
    }

    private Optional<IdempotencyRecord> findRecord(String idempotencyKey) {
        return jdbcClient.sql(SELECT_SQL)
                .params(ENDPOINT, idempotencyKey)
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

    private TransferResponse handleExisting(IdempotencyRecord record, String fingerprint) {
        if (!record.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(
                    "Idempotency key reused with a different request");
        }
        if ("IN_PROGRESS".equals(record.status())) {
            throw new IdempotencyConflictException("Request is in flight", RETRY_AFTER_SECONDS);
        }
        return deserialize(record.responseBody());
    }

    /**
     * A stable SHA-256 hash of the request's semantic fields (from, to, amount,
     * currency) in a fixed order. Detects key reuse: the same key with a
     * different field yields a different fingerprint (422); the same key with the
     * same fields yields the same fingerprint (replay).
     *
     * Property: same logical request => same fingerprint; any differing field =>
     * different fingerprint.
     */
    static String fingerprint(TransferRequest request) {
        try {
            String canonical = String.format(
                    "%d|%d|%d|%s",
                    request.fromAccountId(),
                    request.toAccountId(),
                    request.amountMinor(),
                    request.currency());

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));

            // Convert to hex
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // serialize/deserialize TransferResponse to and from response_body.
    private String serialize(TransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize response", e);
        }
    }

    private TransferResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, TransferResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize response", e);
        }
    }
}