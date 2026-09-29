package com.financeapp.desktop.ui.common;

import com.financeapp.core.money.Money;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Locale;

/**
 * Formatage des montants et des dates pour l'affichage (francais).
 * En mode confidentialite, tout montant affiche devient {@code •••••• €}.
 * A utiliser depuis le thread JavaFX ({@link NumberFormat} n'est pas thread-safe).
 */
public final class Formats {

    public static final Locale LOCALE = Locale.FRANCE;
    private static final String MASK = "••••••";
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM", LOCALE);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("dd/MM/yyyy", LOCALE);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", LOCALE);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", LOCALE);

    private final BooleanProperty privacy = new SimpleBooleanProperty(false);

    public BooleanProperty privacyProperty() {
        return privacy;
    }

    public boolean isPrivacy() {
        return privacy.get();
    }

    /** "1 482,34 €" */
    public String money(Money m) {
        if (privacy.get()) {
            return MASK + " " + symbol(m.currency());
        }
        NumberFormat f = NumberFormat.getCurrencyInstance(LOCALE);
        f.setCurrency(m.currency());
        f.setMinimumFractionDigits(m.amount().scale());
        f.setMaximumFractionDigits(m.amount().scale());
        return f.format(m.amount());
    }

    /** "+1 850,00 €" / "-74,31 €" : le signe est toujours explicite. */
    public String signed(Money m) {
        if (privacy.get()) {
            return (m.isNegative() ? "-" : m.isPositive() ? "+" : "") + MASK + " " + symbol(m.currency());
        }
        return (m.isPositive() ? "+" : "") + money(m);
    }

    /** Montant sans decimales, pour les grands indicateurs : "5 802 €". */
    public String rounded(Money m) {
        if (privacy.get()) {
            return MASK + " " + symbol(m.currency());
        }
        NumberFormat f = NumberFormat.getCurrencyInstance(LOCALE);
        f.setCurrency(m.currency());
        f.setMaximumFractionDigits(0);
        f.setRoundingMode(Money.ROUNDING);
        return f.format(m.amount());
    }

    /** Pourcentage a la francaise : "73,6 %" ; masque en mode confidentialite si demande. */
    public static String percent(java.math.BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString().replace('.', ',') + " %";
    }

    /** Pourcentage signe : "+14 %", "-8 %". */
    public static String signedPercent(java.math.BigDecimal value) {
        if (value == null) {
            return "nouveau";
        }
        return (value.signum() > 0 ? "+" : "") + percent(value);
    }

    public static String symbol(Currency c) {
        return c.getSymbol(LOCALE);
    }

    public static String shortDate(LocalDate d) {
        return d == null ? "" : SHORT.format(d);
    }

    public static String date(LocalDate d) {
        return d == null ? "" : FULL.format(d);
    }

    public static String longDate(LocalDate d) {
        return d == null ? "" : capitalize(LONG.format(d));
    }

    public static String dayMonth(LocalDate d) {
        return d == null ? "" : DAY_MONTH.format(d);
    }

    public static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Classe CSS selon le signe : la couleur double le signe, elle ne le remplace jamais. */
    public static String signClass(Money m) {
        return m.isNegative() ? "amount-negative" : m.isPositive() ? "amount-positive" : "amount-zero";
    }
}
