package com.spademoney.payments.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derived key's shape, pinned so Session 9's replacement is a visible
 * change rather than a silent one.
 */
class LedgerIdempotencyKeysTest {

    @Test
    void theKeyNamesBothThePaymentAndTheStep() {
        assertThat(LedgerIdempotencyKeys.forTransfer("abc-123"))
                .isEqualTo("payment:abc-123:ledger-transfer");
    }

    /**
     * Two payments must never collide inside the Ledger's idempotency scope.
     * The step name alone would do exactly that.
     */
    @Test
    void differentPaymentsProduceDifferentKeys() {
        assertThat(LedgerIdempotencyKeys.forTransfer("one"))
                .isNotEqualTo(LedgerIdempotencyKeys.forTransfer("two"));
    }
}
