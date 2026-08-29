package com.spademoney.payments.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Where the Ledger lives, on both channels.
 *
 * {@code baseUrl} is where commands go: HTTP, synchronous, refusable.
 * {@code eventsTopic} is where facts come back: Kafka, asynchronous, already
 * true. Two addresses for two directions, and the asymmetry is the design --
 * see {@code LedgerEventListener}.
 *
 * Injected by compose as SPADEMONEY_LEDGER_BASE_URL so the same jar runs
 * against localhost and against the compose network.
 */
// @Validated is what makes the @NotBlank below actually run. Without it the
// annotation is decoration, and a missing base URL surfaces as an
// IllegalArgumentException from RestClient on the first payment rather than
// as a refusal to start.
@Validated
@ConfigurationProperties("spademoney.ledger")
public record LedgerProperties(@NotBlank String baseUrl, @NotBlank String eventsTopic) {

    public LedgerProperties {
        eventsTopic = eventsTopic == null || eventsTopic.isBlank()
                ? "spademoney.ledger.events"
                : eventsTopic;
    }

    /**
     * Where records this service could not process are parked. Derived rather
     * than configured separately: two independent properties are two things to
     * keep in step, and Spring's DeadLetterPublishingRecoverer defaults to
     * exactly this suffix.
     */
    public String deadLetterTopic() {
        return eventsTopic + ".DLT";
    }
}
