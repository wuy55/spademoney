package com.spademoney.ledger.money; // ← adjust to your actual package

import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract tests for the Money value object (Milestone 1).
 *
 * Assumed API — ratify or change before implementing:
 * Money.of(long amountMinor, Currency currency) // throws on <= 0
 * Money.parse(String decimal, Currency currency) // BigDecimal-based, rejects
 * excess precision
 * money.plus(Money other) // throws on currency mismatch or long overflow
 * money.amountMinor(), money.currency()
 * No constructor/factory accepting float or double may exist.
 */
class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD"); // 2 fraction digits
    private static final Currency JPY = Currency.getInstance("JPY"); // 0 fraction digits
    private static final Currency BHD = Currency.getInstance("BHD"); // 3 fraction digits
    private static final Currency EUR = Currency.getInstance("EUR");

    // ---------- Construction invariants ----------

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> Money.of(0, USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void rejectsAllNegativeAmounts(@ForAll @LongRange(min = Long.MIN_VALUE, max = -1) long negative) {
        assertThatThrownBy(() -> Money.of(negative, USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void acceptsAllPositiveAmounts(@ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long positive) {
        Money m = Money.of(positive, USD);
        assertThat(m.amountMinor()).isEqualTo(positive);
        assertThat(m.currency()).isEqualTo(USD);
    }

    @Test
    void noFloatOrDoubleFactoryExists() {
        // Compile-time guarantee, verified reflectively as a regression tripwire:
        // no public constructor or static factory on Money accepts float/double.
        for (var m : Money.class.getMethods()) {
            for (var p : m.getParameterTypes()) {
                assertThat(p).isNotIn(float.class, double.class, Float.class, Double.class);
            }
        }
        for (var c : Money.class.getConstructors()) {
            for (var p : c.getParameterTypes()) {
                assertThat(p).isNotIn(float.class, double.class, Float.class, Double.class);
            }
        }
    }

    // ---------- Value semantics ----------

    @Property
    void equalityIsByAmountAndCurrency(@ForAll @LongRange(min = 1) long amount) {
        assertThat(Money.of(amount, USD)).isEqualTo(Money.of(amount, USD));
        assertThat(Money.of(amount, USD)).isNotEqualTo(Money.of(amount, EUR));
        assertThat(Money.of(amount, USD).hashCode()).isEqualTo(Money.of(amount, USD).hashCode());
    }

    // ---------- Arithmetic ----------

    @Property
    void plusIsCommutative(@ForAll @LongRange(min = 1, max = 1_000_000_000L) long a,
            @ForAll @LongRange(min = 1, max = 1_000_000_000L) long b) {
        assertThat(Money.of(a, USD).plus(Money.of(b, USD)))
                .isEqualTo(Money.of(b, USD).plus(Money.of(a, USD)));
    }

    @Property
    void plusAddsMinorUnitsExactly(@ForAll @LongRange(min = 1, max = 1_000_000_000L) long a,
            @ForAll @LongRange(min = 1, max = 1_000_000_000L) long b) {
        assertThat(Money.of(a, USD).plus(Money.of(b, USD)).amountMinor()).isEqualTo(a + b);
    }

    @Test
    void plusRejectsCurrencyMismatch() {
        assertThatThrownBy(() -> Money.of(100, USD).plus(Money.of(100, EUR)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plusThrowsOnOverflowInsteadOfWrapping() {
        Money max = Money.of(Long.MAX_VALUE, USD);
        assertThatThrownBy(() -> max.plus(Money.of(1, USD)))
                .isInstanceOf(ArithmeticException.class);
    }

    // ---------- Decimal-string boundary parsing ----------

    @Test
    void parsesTwoFractionDigitCurrency() {
        assertThat(Money.parse("10.50", USD)).isEqualTo(Money.of(1050, USD));
        assertThat(Money.parse("10.5", USD)).isEqualTo(Money.of(1050, USD));
        assertThat(Money.parse("10", USD)).isEqualTo(Money.of(1000, USD));
    }

    @Test
    void parsesZeroFractionDigitCurrency() {
        assertThat(Money.parse("1500", JPY)).isEqualTo(Money.of(1500, JPY));
    }

    @Test
    void parsesThreeFractionDigitCurrency() {
        assertThat(Money.parse("1.234", BHD)).isEqualTo(Money.of(1234, BHD));
    }

    @Test
    void rejectsExcessPrecisionRatherThanRounding() {
        assertThatThrownBy(() -> Money.parse("10.005", USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("1500.5", JPY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsZeroNegativeAndGarbage() {
        assertThatThrownBy(() -> Money.parse("0", USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("0.00", USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("-5.00", USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("ten dollars", USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("1e3", USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse("", USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.parse(null, USD)).isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void parseAndFormatRoundTrip(@ForAll @LongRange(min = 1, max = 10_000_000_000L) long minor) {
        Money m = Money.of(minor, USD);
        assertThat(Money.parse(m.toDecimalString(), USD)).isEqualTo(m);
    }
}