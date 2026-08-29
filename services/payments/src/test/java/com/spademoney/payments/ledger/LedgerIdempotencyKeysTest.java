package com.spademoney.payments.ledger;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derivation rule, pinned.
 *
 * Small, and worth having: the two properties asserted here are the ones that
 * make the Ledger's idempotency contract mean anything. If the key stopped
 * being a pure function of (saga, step), a retry would become a second effect --
 * which is exactly what this class did on purpose from session 6 to session 8,
 * with a fresh UUID per request and a comment saying so.
 */
class LedgerIdempotencyKeysTest {

    @Test
    void theKeyNamesTheSagaAndTheStep() {
        String sagaId = "3d231cc6-1b12-4a51-9b3e-54f2095b461f";

        assertThat(LedgerIdempotencyKeys.forStep(sagaId, "AUTHORIZE"))
                .isEqualTo("saga:" + sagaId + ":AUTHORIZE");
    }

    /**
     * The same saga and step always produce the same key. This is what makes a
     * retry a replay rather than a second charge.
     */
    @Test
    void theKeyIsStableAcrossAttempts() {
        String sagaId = UUID.randomUUID().toString();

        assertThat(LedgerIdempotencyKeys.forStep(sagaId, "CAPTURE"))
                .isEqualTo(LedgerIdempotencyKeys.forStep(sagaId, "CAPTURE"));
    }

    /**
     * Different steps of one payment never collide inside the Ledger's scope.
     *
     * This is the concrete reason the caller's key cannot simply be forwarded:
     * one key for three steps would make the second collide with the first --
     * same key, different body, 422 IDEMPOTENCY_KEY_REUSED -- and the saga would
     * break on step two, every time.
     */
    @Test
    void differentStepsOfOneSagaGetDifferentKeys() {
        String sagaId = UUID.randomUUID().toString();

        assertThat(LedgerIdempotencyKeys.forStep(sagaId, "AUTHORIZE"))
                .isNotEqualTo(LedgerIdempotencyKeys.forStep(sagaId, "CAPTURE"));
    }

    @Test
    void differentSagasNeverShareAKey() {
        assertThat(LedgerIdempotencyKeys.forStep(UUID.randomUUID().toString(), "AUTHORIZE"))
                .isNotEqualTo(LedgerIdempotencyKeys.forStep(UUID.randomUUID().toString(), "AUTHORIZE"));
    }
}
