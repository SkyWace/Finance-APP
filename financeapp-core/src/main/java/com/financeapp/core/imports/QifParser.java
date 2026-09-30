package com.financeapp.core.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Lecture d'un fichier QIF : une operation par bloc termine par {@code ^},
 * {@code D} = date, {@code T}/{@code U} = montant signe, {@code P} = beneficiaire,
 * {@code M} = memo. Les dates QIF varient selon les logiciels : on essaie les
 * formats europeens puis americains, en acceptant l'apostrophe des annees
 * ({@code 28/09'26}).
 */
public final class QifParser {

    private static final List<String> PATTERNS = List.of("dd/MM/yyyy", "dd/MM/yy", "d/M/yyyy", "d/M/yy",
            "dd-MM-yyyy", "dd.MM.yyyy", "yyyy-MM-dd", "MM/dd/yyyy", "MM/dd/yy", "M/d/yyyy", "M/d/yy");

    public List<ImportedRow> parse(String content) {
        List<ImportedRow> rows = new ArrayList<>();
        String date = null;
        String amount = null;
        String payee = null;
        String memo = null;
        int index = 0;
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("!")) {
                continue;
            }
            char code = line.charAt(0);
            String value = line.substring(1).strip();
            switch (code) {
                case 'D' -> date = value.replace('\'', '/').replace(' ', '0');
                case 'T', 'U' -> amount = value;
                case 'P' -> payee = value;
                case 'M' -> memo = value;
                case '^' -> {
                    index++;
                    rows.add(toRow(index, date, amount, payee, memo));
                    date = amount = payee = memo = null;
                }
                default -> { }
            }
        }
        if (date != null || amount != null) {
            rows.add(toRow(++index, date, amount, payee, memo));
        }
        return rows;
    }

    private static ImportedRow toRow(int index, String date, String amount, String payee, String memo) {
        String label = ((payee == null ? "" : payee) + (memo == null || memo.equals(payee) ? "" : " " + memo)).strip();
        Optional<LocalDate> d = date == null ? Optional.empty() : parseDate(date);
        Optional<BigDecimal> a = AmountText.parse(amount);
        if (d.isEmpty()) {
            return ImportedRow.invalid(index, label, "Date absente ou illisible");
        }
        if (a.isEmpty() || a.get().signum() == 0) {
            return ImportedRow.invalid(index, label, "Montant absent ou nul");
        }
        if (label.isBlank()) {
            return ImportedRow.invalid(index, label, "Libellé vide");
        }
        return new ImportedRow(index, d.get(), label, a.get(), null, null);
    }

    private static Optional<LocalDate> parseDate(String value) {
        for (String p : PATTERNS) {
            Optional<LocalDate> d = CsvMappingGuesser.parseDate(value, p);
            if (d.isPresent()) {
                return d;
            }
        }
        return Optional.empty();
    }
}
