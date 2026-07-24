package com.spademoney.ledger.idempotency;

import com.spademoney.ledger.transfer.TransferRequest;

import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Positive;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests for IdempotencyService.fingerprint. jqwik, plain class, no
 * Spring: fingerprint is a pure function of the request fields.
 *
 * The property that must hold: same logical request => same fingerprint; any
 * different field => different fingerprint.
 */
class FingerprintPropertyTest {

    @Property
    void identicalRequestsProduceIdenticalFingerprints(
            @ForAll long from, @ForAll long to, @ForAll @Positive long amount, @ForAll String currency) {
        TransferRequest a = new TransferRequest(from, to, amount, currency);
        TransferRequest b = new TransferRequest(from, to, amount, currency);

        assertThat(IdempotencyService.fingerprint(a)).isEqualTo(IdempotencyService.fingerprint(b));
    }

    // The important one — a canonical form that sorted account ids would make
    // A→B and B→A collide, quietly letting a reversed transfer replay as the
    // original.
    @Property
    void swappingFromAndToChangesTheFingerprint(
            @ForAll long from, @ForAll long to, @ForAll @Positive long amount, @ForAll String currency) {
        Assume.that(from != to);

        TransferRequest forward = new TransferRequest(from, to, amount, currency);
        TransferRequest reversed = new TransferRequest(to, from, amount, currency);

        assertThat(IdempotencyService.fingerprint(forward))
                .isNotEqualTo(IdempotencyService.fingerprint(reversed));
    }

    @Property
    void changingTheAmountChangesTheFingerprint(
            @ForAll long from, @ForAll long to,
            @ForAll @Positive long amount1, @ForAll @Positive long amount2,
            @ForAll String currency) {
        Assume.that(amount1 != amount2);

        TransferRequest a = new TransferRequest(from, to, amount1, currency);
        TransferRequest b = new TransferRequest(from, to, amount2, currency);

        assertThat(IdempotencyService.fingerprint(a)).isNotEqualTo(IdempotencyService.fingerprint(b));
    }

    @Property
    void changingTheCurrencyChangesTheFingerprint(
            @ForAll long from, @ForAll long to, @ForAll @Positive long amount,
            @ForAll String currency1, @ForAll String currency2) {
        Assume.that(!currency1.equals(currency2));

        TransferRequest a = new TransferRequest(from, to, amount, currency1);
        TransferRequest b = new TransferRequest(from, to, amount, currency2);

        assertThat(IdempotencyService.fingerprint(a)).isNotEqualTo(IdempotencyService.fingerprint(b));
    }
}
