package com.spademoney.payments.payment;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.spademoney.payments.TestcontainersConfiguration;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published contract of POST /payments, with the Ledger replaced by
 * MockRestServiceServer.
 *
 * No real Ledger is started. That is not a shortcut around an integration test —
 * it is the point. What needs proving in this module is the *translation*: that
 * each way the Ledger can answer becomes a distinct, correct answer from
 * Payments. A live Ledger would make the happy path easy and the interesting
 * cases (a 5xx, a read timeout) nearly impossible to provoke on demand. The
 * genuine end-to-end proof is the compose smoke test in Session 12.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMockRestServiceServer
@Import(TestcontainersConfiguration.class)
class PaymentApiTest {

    private static final String BODY = """
            {"payerAccountId":1,"payeeAccountId":2,"amountMinor":2500,"currency":"USD"}
            """;

    @Autowired
    private MockMvc mockMvc;

    /** The stand-in Ledger. Reset between tests by Boot's test execution listener. */
    @Autowired
    private MockRestServiceServer ledger;

    @AfterEach
    void everyExpectedLedgerCallWasMade() {
        ledger.verify();
    }

    @Test
    void aPaymentIsForwardedToTheLedgerAndItsTransactionIdReturned() throws Exception {
        ledger.expect(requestTo("http://localhost:8080/transfers"))
                .andExpect(method(HttpMethod.POST))
                // Payments speaks payer/payee; the Ledger speaks from/to. The
                // translation is asserted here because it is the only place it
                // happens and nothing else would catch it being reversed.
                .andExpect(jsonPath("$.fromAccountId").value(1))
                .andExpect(jsonPath("$.toAccountId").value(2))
                .andExpect(jsonPath("$.amountMinor").value(2500))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"transactionId":42,"status":"POSTED"}
                                """));

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "caller-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(MockMvcResultMatchers.jsonPath("$.ledgerTransactionId").value(42))
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("POSTED"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.paymentId").isNotEmpty())
                // No Location header: there is no GET /payments/{id} to point at,
                // and a Location that 404s is a documented lie.
                .andExpect(MockMvcResultMatchers.header().doesNotExist("Location"));
    }

    /**
     * The single most important assertion in this class.
     *
     * The caller's key names an operation in Payments' scope; the key sent to
     * the Ledger names an operation in the Ledger's. Forwarding the caller's key
     * verbatim is the easy mistake, it looks correct in a happy-path test, and
     * it breaks the moment one payment becomes two Ledger calls.
     */
    @Test
    void theCallersIdempotencyKeyIsNeverForwardedToTheLedger() throws Exception {
        ledger.expect(requestTo("http://localhost:8080/transfers"))
                .andExpect(header("Idempotency-Key", not("caller-key-1")))
                .andExpect(header("Idempotency-Key", matchesPattern("payment:[0-9a-f-]{36}:ledger-transfer")))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"transactionId":42,"status":"POSTED"}
                                """));

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "caller-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isCreated());
    }

    /**
     * A request Payments can reject on its own must never reach the Ledger.
     * With no expectation registered, any outbound call fails this test — which
     * is exactly the assertion wanted.
     */
    @Test
    void anInvalidBodyIsRejectedWithoutCallingTheLedger() throws Exception {
        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "caller-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"payerAccountId":1,"payeeAccountId":2,"amountMinor":-5,"currency":"USD"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void aMissingIdempotencyKeyIsRejectedWithoutCallingTheLedger() throws Exception {
        mockMvc.perform(post("/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void aBlankIdempotencyKeyIsRejectedWithoutCallingTheLedger() throws Exception {
        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "   ")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    /**
     * A 4xx from the Ledger is information, not a transport failure: the money
     * definitively did not move and the reason has a name. Both survive the hop.
     */
    @Test
    void aLedgerRejectionKeepsItsStatusAndErrorCode() throws Exception {
        ledger.expect(requestTo("http://localhost:8080/transfers"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"code":"INSUFFICIENT_FUNDS","message":"available balance 100 < 2500"}
                                """));

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "caller-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                // Distinguishes a rejection Payments relayed from one it made
                // itself -- which starts mattering with Session 9's limit check.
                .andExpect(MockMvcResultMatchers.jsonPath("$.source").value("ledger"));
    }

    /**
     * A 5xx says the request was not processed, so the money did not move and a
     * retry is safe. 502, not 504 -- the difference is the whole point.
     */
    @Test
    void aLedgerServerErrorBecomes502() throws Exception {
        ledger.expect(requestTo("http://localhost:8080/transfers"))
                .andRespond(withServerError());

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "caller-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isBadGateway())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("LEDGER_UNAVAILABLE"));
    }

    /**
     * An unreachable Ledger is 502, not 504 — and this test exists because the
     * first version of the client got it wrong.
     *
     * HttpConnectTimeoutException extends HttpTimeoutException, so a cause-chain
     * check written in the obvious order classifies "never connected" as
     * "connected, then silence". The bug was invisible to every test here and
     * showed up only against a stopped container: a 504 arriving after exactly
     * the 2s connect timeout, reporting an unknown outcome for a request that
     * provably never left the process.
     *
     * The distinction is not pedantry. 502 means a retry is safe; 504 means a
     * retry may double-charge. Getting it backwards makes the safe case
     * unretryable and, worse, makes 504 mean nothing in particular.
     */
    @Test
    void aLedgerThatCannotBeConnectedToBecomes502NotAnAmbiguous504() throws Exception {
        ledger.expect(requestTo("http://localhost:8080/transfers"))
                .andRespond(withException(new HttpConnectTimeoutException("HTTP connect timed out")));

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "caller-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isBadGateway())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("LEDGER_UNAVAILABLE"));
    }

    /** A refused connection says the same thing as a connect timeout: not processed. */
    @Test
    void aRefusedConnectionBecomes502() throws Exception {
        ledger.expect(requestTo("http://localhost:8080/transfers"))
                .andRespond(withException(new ConnectException("Connection refused")));

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "caller-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isBadGateway())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("LEDGER_UNAVAILABLE"));
    }

    /**
     * The deliberately unresolved case (session brief section 9).
     *
     * The read timeout fires and Payments does not know whether the transfer
     * posted. It answers 504 and stops: no retry, no status lookup, no
     * reconciliation shortcut. Every one of those would be a guess dressed as a
     * fix, and each would have to be torn out again once the outbox (Session 7),
     * the inbox (Session 8) and the deterministic saga key (Session 9) make the
     * operation genuinely recoverable.
     *
     * This test pins the honest behaviour so nobody "improves" it by accident.
     */
    @Test
    void aLedgerReadTimeoutBecomes504AndIsNotRetried() throws Exception {
        ledger.expect(requestTo("http://localhost:8080/transfers"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "caller-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
                .andExpect(status().isGatewayTimeout())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("LEDGER_TIMEOUT"));

        // Exactly one outbound call. ledger.verify() in @AfterEach would fail if
        // a retry had fired a second request against a server expecting one.
    }
}
