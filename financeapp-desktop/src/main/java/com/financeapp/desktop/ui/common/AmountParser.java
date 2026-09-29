package com.financeapp.desktop.ui.common;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Analyse d'un montant saisi a la francaise ou a l'anglaise :
 * "1 234,56", "1234.56", "-12,5", "1 234 €". Jamais de passage par double.
 */
public final class AmountParser {

    private AmountParser() {
    }

    public static Optional<BigDecimal> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.strip()
                .replace("€", "")
                .replace(" ", "")
                .replace(" ", "")
                .replace(" ", "")
                .replace('−', '-')
                .replace(',', '.');
        if (s.isEmpty() || s.indexOf('.') != s.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(s));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Representation editable d'un montant : "1234,56". */
    public static String toEditable(BigDecimal value) {
        return value == null ? "" : value.toPlainString().replace('.', ',');
    }
}
