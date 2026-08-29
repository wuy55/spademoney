package com.spademoney.payments.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;

import com.spademoney.payments.TestcontainersConfiguration;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published contract of POST and GET /payments.
 *
 * The saga driver's trigger is off in tests, so accepting a payment here writes
 * a saga and nothing else — which is exactly the contract being asserted. Not
 * one of these tests needs the Ledger, and the {@code MockRestServiceServer}
 * is present only to make an accidental outbound call fail loudly rather than
 * hit a real socket.
 *
 * What the saga then does with the payment is {@code PaymentSagaTest}.
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
    @Autowired
    private MockRestServiceServer ledger;
    @Autowired
    private JdbcClient jdbcClient;

    /**
     * No expectations are registered anywhere in this class, and with
     * MockRestServiceServer that is itself an assertion: any outbound call at
     * all fails the test. Accepting a payment must touch nothing but this
     * service's own database.
     */
    @org.junit.jupiter.api.AfterEach
    void theLedgerWasNeverCalled() {
        ledger.verify();
    }

    @BeforeEach
    void reset() {
        jdbcClient.sql("TRUNCATE sagas, saga_steps, limit_consumptions, payment_limits CASCADE").update();
    }

    /**
     * 202, not 201, and a Location header that resolves.
     *
     * Sessions 6-8 shipped a 201 with deliberately NO Location, because the
     * obvious /payments/{id} would have pointed at a 404. The saga is the
     * resource that was missing, so the header arrives with the thing it names.
     */
    @Test
    void anAcceptedPaymentAnswers202WithALocationThatResolves() throws Exception {
        String location = mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.paymentId").value(matchesPattern(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sagaStatus").value("RUNNING"))
                .andExpect(jsonPath("$.amountMinor").value(2500));
    }

    /**
     * Case 2 of the four-case contract: a replay returns the same payment and
     * creates no second one.
     */
    @Test
    void replayingTheSameKeyWithTheSameBodyReturnsTheSamePayment() throws Exception {
        String first = paymentIdFrom(BODY, "key-replay");
        String second = paymentIdFrom(BODY, "key-replay");

        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
        org.assertj.core.api.Assertions.assertThat(sagaCount()).isEqualTo(1);
    }

    /**
     * Case 3: a key reused for a DIFFERENT payment is a client bug and is
     * reported, not absorbed. Absorbing it would answer with the first payment's
     * status for a request describing a different one.
     */
    @Test
    void reusingAKeyForADifferentPaymentIs422() throws Exception {
        mockMvc.perform(post("/payments")
                .header("Idempotency-Key", "key-reuse")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-reuse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerAccountId":1,"payeeAccountId":2,"amountMinor":9900,"currency":"USD"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        org.assertj.core.api.Assertions.assertThat(sagaCount()).isEqualTo(1);
    }

    @Test
    void anInvalidBodyIsRejectedAndNoSagaIsWritten() throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerAccountId":1,"payeeAccountId":2,"amountMinor":-1,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        org.assertj.core.api.Assertions.assertThat(sagaCount()).isZero();
    }

    @Test
    void aMissingIdempotencyKeyIsRejectedWithAMachineReadableCode() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void aBlankIdempotencyKeyIsRejected() throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    /**
     * A malformed id and an unknown id both answer 404. The distinction a caller
     * can act on is "no such payment"; whether the id was the wrong shape or
     * merely unknown is not their problem, and a 400 here would invite clients
     * to branch on it.
     */
    @Test
    void unknownPaymentIdsAre404WhateverShapeTheyAre() throws Exception {
        mockMvc.perform(get("/payments/not-a-uuid")).andExpect(status().isNotFound());
        mockMvc.perform(get("/payments/8a1a3b4c-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    /**
     * A body missing a numeric field.
     *
     * Worth its own test because of where it fails. {@code amountMinor} is a
     * primitive {@code long}, and Jackson 3 turns ON FAIL_ON_NULL_FOR_PRIMITIVES
     * -- which Jackson 2 had off -- so an omitted field breaks during
     * deserialization, before @Valid ever runs. It arrives as
     * HttpMessageNotReadableException, not MethodArgumentNotValidException, and
     * until that was handled it produced a 400 with a completely empty body: no
     * code, no message, on the most common client mistake there is.
     */
    @Test
    void aBodyMissingANumericFieldStillGetsAMachineReadableError() throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-missing-field")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerAccountId":1,"payeeAccountId":2,"currency":"USD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        org.assertj.core.api.Assertions.assertThat(sagaCount()).isZero();
    }

    @Test
    void theErrorEnvelopeMatchesTheLedgersSoOneParserHandlesBoth() throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-envelope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerAccountId":1,"payeeAccountId":2,"amountMinor":-1,"currency":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    private String paymentIdFrom(String body, String key) throws Exception {
        String json = mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(json).get("paymentId").asString();
    }

    private long sagaCount() {
        return jdbcClient.sql("SELECT count(*) FROM sagas").query(Long.class).single();
    }
}
