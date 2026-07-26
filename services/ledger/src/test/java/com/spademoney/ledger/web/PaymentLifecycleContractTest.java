package com.spademoney.ledger.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.spademoney.ledger.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published HTTP contract for the payment lifecycle, exercised over real
 * HTTP rather than through the services.
 *
 * This exists because docs/openapi.yaml is HAND-WRITTEN. A generated spec cannot
 * lie about the code; a hand-written one can drift the moment a status code or a
 * field name changes. These tests are what keep it honest: every status, error
 * `code` and Location header asserted below is a line in that document, so a
 * change that contradicts the spec fails the build instead of shipping a false
 * contract.
 *
 * Error codes covered: HOLD_NOT_ACTIVE, HOLD_EXPIRED, CAPTURE_EXCEEDS_HOLD,
 * REFUND_EXCEEDS_CAPTURED, UNREFUNDABLE, INSUFFICIENT_FUNDS,
 * IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_KEY_REQUIRED, NOT_FOUND.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentLifecycleContractTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbcClient;

    private Long cash;
    private Long payer;
    private Long payee;

    @BeforeEach
    void reset() {
        jdbcClient.sql("""
                TRUNCATE holds, entries, transactions, idempotency_keys, accounts
                RESTART IDENTITY CASCADE
                """).update();
        cash = account("CASH");
        payer = account("USER_WALLET");
        payee = account("USER_WALLET");
        fund(payer, 100_000L);
    }

    // ---------------------------------------------------------------- happy path

    // The whole card-rail lifecycle over HTTP, in the order a real integration
    // would walk it: authorize -> read the hold -> capture part of it -> refund
    // part of that.
    @Test
    void theFullAuthorizeCaptureRefundLifecycle() throws Exception {
        // --- authorize: 201 + Location, and no money has moved yet
        MvcResult authorized = mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "auth-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody(75_000L, 3600L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.amountMinor").value(75_000))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn();

        long holdId = jsonLong(authorized, "holdId");
        assertThat(authorized.getResponse().getHeader("Location")).isEqualTo("/holds/" + holdId);

        // Reserved, not spent: posted is untouched, available is down.
        mockMvc.perform(get("/accounts/{id}/balance", payer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postedBalance").value(100_000))
                .andExpect(jsonPath("$.heldMinor").value(75_000))
                .andExpect(jsonPath("$.availableBalance").value(25_000));

        // --- the Location header actually resolves
        mockMvc.perform(get("/holds/{id}", holdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.payeeAccountId").value(payee));

        // --- partial capture: 200, remainder released
        MvcResult captured = mockMvc.perform(post("/holds/{id}/capture", holdId)
                .header("Idempotency-Key", "cap-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":43100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedMinor").value(43_100))
                .andExpect(jsonPath("$.releasedMinor").value(31_900))
                .andReturn();

        long captureTxn = jsonLong(captured, "transactionId");

        // Now the money has actually moved, and nothing is reserved any more.
        mockMvc.perform(get("/accounts/{id}/balance", payer))
                .andExpect(jsonPath("$.postedBalance").value(56_900))
                .andExpect(jsonPath("$.heldMinor").value(0))
                .andExpect(jsonPath("$.availableBalance").value(56_900));

        // --- provenance is visible: a capture is not a transfer
        mockMvc.perform(get("/transfers/{id}", captureTxn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CAPTURE"))
                .andExpect(jsonPath("$.reversesTransactionId").doesNotExist())
                .andExpect(jsonPath("$.entries.length()").value(2));

        // --- partial refund of the capture: 201 + Location on the refund's txn
        MvcResult refunded = mockMvc.perform(post("/refunds")
                .header("Idempotency-Key", "ref-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":" + captureTxn + ",\"amountMinor\":13100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversedTransactionId").value(captureTxn))
                .andExpect(jsonPath("$.totalRefundedMinor").value(13_100))
                .andExpect(jsonPath("$.remainingRefundableMinor").value(30_000))
                .andReturn();

        long refundTxn = jsonLong(refunded, "refundTransactionId");
        assertThat(refunded.getResponse().getHeader("Location")).isEqualTo("/transfers/" + refundTxn);

        // --- a refund names what it undoes
        mockMvc.perform(get("/transfers/{id}", refundTxn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REFUND"))
                .andExpect(jsonPath("$.reversesTransactionId").value(captureTxn));

        mockMvc.perform(get("/accounts/{id}/balance", payer))
                .andExpect(jsonPath("$.postedBalance").value(70_000));
    }

    @Test
    void voidingReleasesTheReservationOverHttp() throws Exception {
        long holdId = authorize("auth-void", 30_000L, 3600L);

        mockMvc.perform(post("/holds/{id}/void", holdId)
                .header("Idempotency-Key", "void-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));

        mockMvc.perform(get("/accounts/{id}/balance", payer))
                .andExpect(jsonPath("$.heldMinor").value(0))
                .andExpect(jsonPath("$.availableBalance").value(100_000));
    }

    // ------------------------------------------------------------- error taxonomy

    @Test
    void resolvingAnAlreadyResolvedHoldIs422HoldNotActive() throws Exception {
        long holdId = authorize("auth-2", 30_000L, 3600L);
        mockMvc.perform(post("/holds/{id}/void", holdId).header("Idempotency-Key", "v-1"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/holds/{id}/void", holdId).header("Idempotency-Key", "v-2"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));

        mockMvc.perform(post("/holds/{id}/capture", holdId)
                .header("Idempotency-Key", "c-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":1000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("HOLD_NOT_ACTIVE"));
    }

    // A lapsed hold still READS as ACTIVE, so the client is told the real reason
    // rather than "already resolved".
    @Test
    void capturingALapsedHoldIs422HoldExpired() throws Exception {
        long holdId = insertLapsedHold(30_000L);

        mockMvc.perform(get("/holds/{id}", holdId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/holds/{id}/capture", holdId)
                .header("Idempotency-Key", "c-exp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":30000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
    }

    @Test
    void capturingMoreThanAuthorizedIs422CaptureExceedsHold() throws Exception {
        long holdId = authorize("auth-3", 30_000L, 3600L);

        mockMvc.perform(post("/holds/{id}/capture", holdId)
                .header("Idempotency-Key", "c-big")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":30001}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CAPTURE_EXCEEDS_HOLD"));

        // The rejected capture rolled back entirely: still usable.
        mockMvc.perform(get("/holds/{id}", holdId))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void refundingBeyondTheOriginalIs422RefundExceedsCaptured() throws Exception {
        long transferTxn = transfer("t-1", 30_000L);

        mockMvc.perform(post("/refunds")
                .header("Idempotency-Key", "r-big")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":" + transferTxn + ",\"amountMinor\":30001}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_CAPTURED"));
    }

    @Test
    void refundingARefundIs422Unrefundable() throws Exception {
        long transferTxn = transfer("t-2", 30_000L);

        MvcResult refunded = mockMvc.perform(post("/refunds")
                .header("Idempotency-Key", "r-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":" + transferTxn + ",\"amountMinor\":30000}"))
                .andExpect(status().isCreated())
                .andReturn();

        long refundTxn = jsonLong(refunded, "refundTransactionId");

        mockMvc.perform(post("/refunds")
                .header("Idempotency-Key", "r-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":" + refundTxn + ",\"amountMinor\":1000}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNREFUNDABLE"));
    }

    // A hold blocks a transfer that posted balance alone would allow -- the
    // available-vs-posted invariant, asserted at the HTTP boundary.
    @Test
    void aHoldMakesAnOtherwiseAffordableTransferReturn422InsufficientFunds() throws Exception {
        authorize("auth-4", 30_000L, 3600L);

        mockMvc.perform(post("/transfers")
                .header("Idempotency-Key", "t-blocked")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":" + payer + ",\"toAccountId\":" + payee
                        + ",\"amountMinor\":80000,\"currency\":\"USD\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void unknownHoldsAndTransactionsAre404() throws Exception {
        mockMvc.perform(get("/holds/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(post("/holds/{id}/void", 999_999L).header("Idempotency-Key", "v-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(post("/refunds")
                .header("Idempotency-Key", "r-404")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":999999,\"amountMinor\":1000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void replayingAnAuthorizeReturnsTheStoredResponseAndCreatesOneHold() throws Exception {
        String body = authorizeBody(30_000L, 3600L);

        String first = mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "replay")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "replay")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).as("the stored response is replayed verbatim").isEqualTo(first);
        assertThat(holdCount()).as("the replay must not create a second hold").isEqualTo(1);
    }

    @Test
    void reusingAKeyWithADifferentBodyIs422KeyReused() throws Exception {
        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "dup")
                .contentType(MediaType.APPLICATION_JSON).content(authorizeBody(30_000L, 3600L)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "dup")
                .contentType(MediaType.APPLICATION_JSON).content(authorizeBody(31_000L, 3600L)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(holdCount()).isEqualTo(1);
    }

    // One key against a DIFFERENT hold must not quietly get its own scope: the
    // hold id lives in the fingerprint, not the key's endpoint column.
    @Test
    void oneVoidKeyAgainstADifferentHoldIs422KeyReused() throws Exception {
        long first = authorize("a-1", 10_000L, 3600L);
        long second = authorize("a-2", 10_000L, 3600L);

        mockMvc.perform(post("/holds/{id}/void", first).header("Idempotency-Key", "shared"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/holds/{id}/void", second).header("Idempotency-Key", "shared"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(get("/holds/{id}", second))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aMissingOrBlankIdempotencyKeyIs400() throws Exception {
        mockMvc.perform(post("/holds")
                .contentType(MediaType.APPLICATION_JSON).content(authorizeBody(30_000L, 3600L)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "   ")
                .contentType(MediaType.APPLICATION_JSON).content(authorizeBody(30_000L, 3600L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void anOutOfBandStrandedKeyIs409WithRetryAfter() throws Exception {
        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "stranded")
                .contentType(MediaType.APPLICATION_JSON).content(authorizeBody(30_000L, 3600L)))
                .andExpect(status().isCreated());

        // Strand the row the only way the single-transaction design allows:
        // out of band. Nothing in normal operation can leave IN_PROGRESS behind.
        jdbcClient.sql("UPDATE idempotency_keys SET status='IN_PROGRESS' WHERE idempotency_key='stranded'")
                .update();

        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "stranded")
                .contentType(MediaType.APPLICATION_JSON).content(authorizeBody(30_000L, 3600L)))
                .andExpect(status().isConflict())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));
    }

    @Test
    void aMalformedBodyIs400() throws Exception {
        // expiresInSeconds is @Positive; -1 fails validation at the boundary,
        // before any service sees it.
        mockMvc.perform(post("/holds")
                .header("Idempotency-Key", "bad-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody(30_000L, -1L)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    private String authorizeBody(long amountMinor, long expiresInSeconds) {
        return "{\"payerAccountId\":" + payer + ",\"payeeAccountId\":" + payee
                + ",\"amountMinor\":" + amountMinor + ",\"currency\":\"USD\""
                + ",\"expiresInSeconds\":" + expiresInSeconds + "}";
    }

    private long authorize(String key, long amountMinor, long expiresInSeconds) throws Exception {
        MvcResult result = mockMvc.perform(post("/holds")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(authorizeBody(amountMinor, expiresInSeconds)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(result, "holdId");
    }

    private long transfer(String key, long amountMinor) throws Exception {
        MvcResult result = mockMvc.perform(post("/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":" + payer + ",\"toAccountId\":" + payee
                        + ",\"amountMinor\":" + amountMinor + ",\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(result, "transactionId");
    }

    private static long jsonLong(MvcResult result, String field) throws Exception {
        String json = result.getResponse().getContentAsString();
        String marker = "\"" + field + "\":";
        int start = json.indexOf(marker) + marker.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private Long account(String type) {
        return jdbcClient.sql("INSERT INTO accounts(type, currency) VALUES (?, 'USD') RETURNING id")
                .param(type).query(Long.class).single();
    }

    private void fund(Long walletId, long amountMinor) {
        Long txnId = jdbcClient.sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                VALUES
                    (?, ?, 'DEBIT',  ?, 'USD'),
                    (?, ?, 'CREDIT', ?, 'USD')
                """).params(txnId, cash, amountMinor, txnId, walletId, amountMinor).update();
    }

    private long insertLapsedHold(long amountMinor) {
        return jdbcClient.sql("""
                INSERT INTO holds(account_id, payee_account_id, amount_minor, currency, expires_at)
                VALUES (?, ?, ?, 'USD', now() - interval '1 second')
                RETURNING id
                """).params(payer, payee, amountMinor).query(Long.class).single();
    }

    private int holdCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM holds").query(Integer.class).single();
    }
}
