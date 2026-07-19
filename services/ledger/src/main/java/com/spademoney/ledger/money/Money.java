package com.spademoney.ledger.money;
import java.util.Currency;


public record Money(long amountMinor, Currency currency) {
    public Money {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (currency == null) {
            throw new IllegalArgumentException("Currency is required");
        }
    }

    public static Money of(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }
}
