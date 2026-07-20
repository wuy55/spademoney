package com.spademoney.ledger.money;
import java.util.Currency;
import java.math.BigDecimal;


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

    public Money plus(Money other) {
        if (!this.currency.equals(other.currency)){
            throw new IllegalArgumentException(
                "Cannot add difference currencies: " + this.currency + " + " + other.currency
            );
        }
        return new Money(Math.addExact(this.amountMinor, other.amountMinor), this.currency);
    }

    public static Money parse(String decimal, Currency currency) {

        // validate inputs
        if (decimal == null || decimal.isBlank()) {
            throw new IllegalArgumentException("Amount string is required");
        }
        
        if (currency == null) {
            throw new IllegalArgumentException("Currency is required");
        }

        // parse the string into BigDecimal
        BigDecimal bd;
        try {
            bd = new BigDecimal(decimal);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid decimal format: " + decimal, e);
        }

        // No scientific notation
        if (!bd.toPlainString().equals(decimal)){
            throw new IllegalArgumentException("Scientific notation not allowed: " + decimal);
        }
        // Must be positive
        if (bd.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + decimal);
        }

        // Can't have more decimal places than the currency allows
        int maxScale = currency.getDefaultFractionDigits();
        if (bd.scale() > maxScale) {
            throw new IllegalArgumentException(
                "Amount has " + bd.scale() + " decimal places, but " + 
                currency.getCurrencyCode() + " allows only " + maxScale
            );
        }
        // convert to minor units (multiply by 10^maxScale, then to long)
        BigDecimal minorUnits = bd.movePointRight(maxScale);
        long amountMinor;
        try {
            amountMinor = minorUnits.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                "Amount is too large for long: " + decimal, e
            );
        }

        return Money.of(amountMinor, currency);
    }

    public String toDecimalString()  {
    
        int scale = Math.max(0, currency.getDefaultFractionDigits());
        BigDecimal bd = BigDecimal.valueOf(amountMinor, scale);
        return bd.toPlainString();   
    }
}
