package com.financeapp.core.imports;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Lecture des montants tels que les exportent les banques, sans jamais passer
 * par {@code double} : "1 234,56", "-12.50", "1.234,56", "1,234.56",
 * "(12,00)" (negatif comptable), "12,00 €", "+5", "12,5-".
 */
public final class AmountText {

    private AmountText() {
    }

    public static Optional<BigDecimal> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip()
                .replace(" ", "").replace(" ", "").replace(" ", "")
                .replace("€", "").replace("EUR", "").replace("eur", "")
                .replace('−', '-');
        if (s.isEmpty()) {
            return Optional.empty();
        }
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        }
        if (s.endsWith("-")) {
            negative = !negative;
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("-")) {
            negative = !negative;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        int comma = s.lastIndexOf(',');
        int dot = s.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            // Le dernier separateur rencontre est le separateur decimal.
            if (comma > dot) {
                s = s.replace(".", "").replace(',', '.');
            } else {
                s = s.replace(",", "");
            }
        } else if (comma >= 0) {
            s = s.indexOf(',') == comma ? s.replace(',', '.') : s.replace(",", "");
        } else if (dot >= 0 && s.indexOf('.') != dot) {
            s = s.replace(".", ""); // "1.234.567" : separateurs de milliers
        }
        if (!s.matches("\\d+(\\.\\d+)?")) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(s);
        return Optional.of(negative ? value.negate() : value);
    }
}
