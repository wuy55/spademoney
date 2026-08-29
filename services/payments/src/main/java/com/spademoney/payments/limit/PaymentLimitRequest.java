package com.spademoney.payments.limit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentLimitRequest(
        @Positive long capMinor,
        @NotBlank @Size(min = 3, max = 3) String currency) {
}
