package com.spademoney.ledger.transfer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import com.spademoney.ledger.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contract tests for the idempotency behaviour of POST /transfers at the HTTP
 * layer: status codes, the Retry-After header, and the response body shape.
 *
 * HTTP contract:
 *   POST /transfers
 *     header  Idempotency-Key: <key>       (required; missing -> 400)
 *     body    {"fromAccountId":1,"toAccountId":2,"amountMinor":5000,"currency":"USD"}
 *     201     {"transactionId":42,"status":"POSTED"}
 *     422     key reused with a different request
 *     409     original request for this key still in flight (+ Retry-After header)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TransferIdempotencyContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    private Long sender;
    private Long receiver;
    private Long cash;

    @BeforeEach
    void setup() {
        cash = createAccount("CASH", "USD");
        sender = createAccount("USER_WALLET", "USD");
        receiver = createAccount("USER_WALLET", "USD");
        fundAccount(cash, sender, 100_000L);
    }

    // New key -> process and record COMPLETED.
    @Test
    void newKeyProcessesTransferAndRecordsCompleted() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sender, receiver, 5_000L, "USD")))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.transactionId").exists());

        // The transfer actually happened...
        assertThat(getBalance(sender)).isEqualTo(95_000L);
        assertThat(getBalance(receiver)).isEqualTo(5_000L);

        // ...and the key is recorded COMPLETED, atomically with the money movement.
        String status = jdbcClient
                .sql("SELECT status FROM idempotency_keys WHERE endpoint=? AND idempotency_key=?")
                .params("/transfers", "key-1")
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("COMPLETED");
    }

    // Retry, same key + same request -> replay, never re-execute.
    @Test
    void sameKeySameRequestReplaysResponseAndAppliesExactlyOnce() throws Exception {
        String req = body(sender, receiver, 5_000L, "USD");

        String first = mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();

        // The stored response is replayed verbatim...
        assertThat(second).isEqualTo(first);
        // ...and the money moved EXACTLY ONCE (95_000, not 90_000).
        assertThat(getBalance(sender)).isEqualTo(95_000L);
        assertThat(getBalance(receiver)).isEqualTo(5_000L);
    }

    // Same key, different request -> 422.
    @Test
    void sameKeyDifferentRequestIsRejected() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sender, receiver, 5_000L, "USD")))
                .andExpect(status().is2xxSuccessful());

        // Same key, different amount -> a different logical request -> reject.
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sender, receiver, 9_999L, "USD")))
                .andExpect(status().isUnprocessableContent()); // 422

        // The second (rejected) request did NOT execute.
        assertThat(getBalance(sender)).isEqualTo(95_000L);
    }

    // Key still in flight -> 409 + Retry-After. The handler checks the
    // fingerprint before the status, so the stranded row must carry the
    // request's real fingerprint: complete a transfer, then flip its row back
    // to IN_PROGRESS to simulate an original that never finished.
    @Test
    void keyInFlightReturns409WithRetryAfter() throws Exception {
        String req = body(sender, receiver, 5_000L, "USD");

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().is2xxSuccessful());

        jdbcClient.sql("""
                        UPDATE idempotency_keys SET status = 'IN_PROGRESS'
                        WHERE endpoint = ? AND idempotency_key = ?
                        """)
                .params("/transfers", "key-4")
                .update();

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(req))
                .andExpect(status().isConflict())          // 409
                .andExpect(header().exists("Retry-After"));

        // The retry moved no additional money: only the first transfer's 5_000.
        assertThat(getBalance(sender)).isEqualTo(95_000L);
    }

    // ---------- helpers ----------

    private String body(Long from, Long to, long amountMinor, String currency) {
        return """
                {"fromAccountId":%d,"toAccountId":%d,"amountMinor":%d,"currency":"%s"}
                """.formatted(from, to, amountMinor, currency);
    }

    private Long createAccount(String type, String currency) {
        return jdbcClient
                .sql("INSERT INTO accounts(type, currency) VALUES (?, ?) RETURNING id")
                .params(type, currency)
                .query(Long.class)
                .single();
    }

    private void fundAccount(Long cashId, Long walletId, long amountMinor) {
        Long txnId = jdbcClient
                .sql("INSERT INTO transactions(type) VALUES ('TRANSFER') RETURNING id")
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        INSERT INTO entries(transaction_id, account_id, direction, amount_minor, currency)
                        VALUES
                            (?, ?, 'DEBIT',  ?, 'USD'),
                            (?, ?, 'CREDIT', ?, 'USD')
                        """)
                .params(txnId, cashId, amountMinor, txnId, walletId, amountMinor)
                .update();
    }

    private long getBalance(Long accountId) {
        return jdbcClient
                .sql("SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM entries WHERE account_id = ?")
                .param(accountId)
                .query(Long.class)
                .single();
    }
}