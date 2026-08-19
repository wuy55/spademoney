package com.spademoney.payments.ledger;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Where the Ledger lives. Injected by compose as SPADEMONEY_LEDGER_BASE_URL so
 * the same jar runs against localhost and against the compose network.
 */
// @Validated is what makes the @NotBlank below actually run. Without it the
// annotation is decoration, and a missing base URL surfaces as an
// IllegalArgumentException from RestClient on the first payment rather than
// as a refusal to start.
@Validated
@ConfigurationProperties("spademoney.ledger")
public record LedgerProperties(@NotBlank String baseUrl) {
}
