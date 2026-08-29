package com.spademoney.payments.limit;

/** A cap and how much of it is currently spoken for. Both numbers derived. */
public record PaymentLimitView(long accountId, long capMinor, String currency, long consumedMinor) {

    public long remainingMinor() {
        return capMinor - consumedMinor;
    }
}
