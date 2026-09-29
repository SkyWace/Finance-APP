package com.financeapp.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Montant monetaire immuable.
 *
 * <p>Regles de precision, definies ici et nulle part ailleurs :
 * <ul>
 *   <li>valeur en {@link BigDecimal}, jamais de {@code float}/{@code double} ;</li>
 *   <li>echelle = nombre de decimales de la devise
 *       ({@link Currency#getDefaultFractionDigits()}, 2 pour l'euro) ;</li>
 *   <li>arrondi {@link #ROUNDING} (arrondi bancaire, sans biais cumule) applique
 *       uniquement lors de la construction ou d'une division/multiplication ;</li>
 *   <li>stockage en base en unites mineures (centimes) via {@link #toMinorUnits()}.</li>
 * </ul>
 * Les operations entre devises differentes sont refusees : aucune conversion
 * implicite.
 */
public final class Money implements Comparable<Money> {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final Currency EUR = Currency.getInstance("EUR");

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.currency = Objects.requireNonNull(currency, "currency");
        this.amount = Objects.requireNonNull(amount, "amount").setScale(scaleOf(currency), ROUNDING);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money eur(String amount) {
        return of(amount, EUR);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /** Reconstruit un montant a partir d'unites mineures (centimes pour l'euro). */
    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(BigDecimal.valueOf(minorUnits, scaleOf(currency)), currency);
    }

    private static int scaleOf(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        return digits < 0 ? 2 : digits;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    /** Valeur en unites mineures, exacte par construction (echelle deja fixee). */
    public long toMinorUnits() {
        return amount.movePointRight(amount.scale()).longValueExact();
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return amount.signum() < 0 ? negate() : this;
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money divide(BigDecimal divisor) {
        return new Money(amount.divide(divisor, scaleOf(currency) + 4, ROUNDING), currency);
    }

    public int signum() {
        return amount.signum();
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isSameCurrency(Money other) {
        return currency.equals(other.currency);
    }

    private void requireSameCurrency(Money other) {
        if (!isSameCurrency(other)) {
            throw new IllegalArgumentException(
                    "Devises differentes : " + currency.getCurrencyCode() + " / " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m && currency.equals(m.currency) && amount.compareTo(m.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    /** Representation technique (non localisee), utile en test et en debug. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
