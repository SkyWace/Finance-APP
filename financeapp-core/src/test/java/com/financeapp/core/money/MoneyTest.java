package com.financeapp.core.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void scaleIsFixedByCurrency() {
        assertEquals(2, Money.eur("10").amount().scale());
        assertEquals("10.00 EUR", Money.eur("10").toString());
        assertEquals(0, Money.of("1500", Currency.getInstance("JPY")).amount().scale());
    }

    @Test
    void roundsHalfEven() {
        assertEquals(Money.eur("0.12"), Money.eur("0.125"));
        assertEquals(Money.eur("0.14"), Money.eur("0.135"));
        assertEquals(Money.eur("-0.12"), Money.eur("-0.125"));
    }

    @Test
    void additionIsExactWhereDoubleIsNot() {
        Money sum = Money.zero(Money.EUR);
        for (int i = 0; i < 10; i++) {
            sum = sum.plus(Money.eur("0.10"));
        }
        assertEquals(Money.eur("1.00"), sum);
    }

    @Test
    void minorUnitsRoundTrip() {
        Money m = Money.eur("-1482.34");
        assertEquals(-148234L, m.toMinorUnits());
        assertEquals(m, Money.ofMinor(-148234L, Money.EUR));
    }

    @Test
    void divisionRoundsToCurrencyScale() {
        // 1 750 euros sur 15 mois = 116,666... -> 116,67
        assertEquals(Money.eur("116.67"), Money.eur("1750").divide(BigDecimal.valueOf(15)));
        // 100 / 3 = 33,333... -> 33,33 (aucun centime invente)
        assertEquals(Money.eur("33.33"), Money.eur("100").divide(BigDecimal.valueOf(3)));
    }

    @Test
    void refusesMixingCurrencies() {
        Money usd = Money.of("1", Currency.getInstance("USD"));
        assertThrows(IllegalArgumentException.class, () -> Money.eur("1").plus(usd));
        assertThrows(IllegalArgumentException.class, () -> Money.eur("1").compareTo(usd));
    }

    @Test
    void equalityIgnoresScaleOfInput() {
        assertEquals(Money.eur("5"), Money.eur("5.000"));
        assertEquals(Money.eur("5").hashCode(), Money.eur("5.000").hashCode());
    }
}
