package com.financeapp.core.imports;

import com.financeapp.core.text.LabelNormalizer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Optional;

/**
 * Propose une correspondance de colonnes a partir des en-tetes usuels des
 * banques francaises (Date, Libelle, Debit, Credit, Montant...) et, a defaut,
 * du contenu (colonne de dates, colonne de montants). L'utilisateur peut
 * toujours corriger la proposition dans l'assistant.
 */
public final class CsvMappingGuesser {

    /** Formats essayes, du plus courant en France au moins courant. */
    public static final List<String> DATE_PATTERNS = List.of(
            "dd/MM/yyyy", "yyyy-MM-dd", "dd-MM-yyyy", "dd.MM.yyyy", "dd/MM/yy", "yyyy/MM/dd", "d/M/yyyy", "MM/dd/yyyy");

    private static final int SAMPLE = 30;

    private static final String[] DATE_KEYS = {"date operation", "date de l operation", "date", "dateop", "date comptable",
            "date valeur", "booking date"};
    private static final String[] LABEL_KEYS = {"libelle", "libelle operation", "label", "description", "intitule",
            "nature", "details", "detail", "operation"};
    private static final String[] DETAIL_KEYS = {"libelle complementaire", "complement", "informations complementaires",
            "info complementaire", "reference"};
    private static final String[] AMOUNT_KEYS = {"montant", "amount", "montant eur", "somme"};
    private static final String[] DEBIT_KEYS = {"debit", "debit euros", "debit eur", "sortie", "depense", "retrait"};
    private static final String[] CREDIT_KEYS = {"credit", "credit euros", "credit eur", "entree", "recette", "versement"};

    public CsvMapping guess(CsvTable table) {
        int columns = table.columnCount();
        // 1. En-tete reconnue par ses mots-cles (apres un eventuel preambule : numero de compte, periode...).
        int headerIndex = -1;
        for (int i = 0; i < Math.min(15, table.rows().size()); i++) {
            List<String> row = table.rows().get(i);
            if (find(row, DATE_KEYS) != null && (find(row, LABEL_KEYS) != null || find(row, AMOUNT_KEYS) != null
                    || find(row, DEBIT_KEYS) != null)) {
                headerIndex = i;
                break;
            }
        }
        int headerRows;
        List<String> header;
        boolean hasHeader;
        if (headerIndex >= 0) {
            headerRows = headerIndex + 1;
            header = table.rows().get(headerIndex);
            hasHeader = true;
        } else {
            // 2. Sinon : premiere ligne comportant une date lisible = premiere ligne de donnees.
            int first = 0;
            while (first < table.rows().size() - 1
                    && table.rows().get(first).stream().noneMatch(c -> datePattern(List.of(c)).isPresent())) {
                first++;
            }
            headerRows = first;
            header = table.rows().get(first);
            hasHeader = false;
        }
        List<List<String>> data = table.rows().subList(Math.min(headerRows, table.rows().size()), table.rows().size());

        Integer date = hasHeader ? find(header, DATE_KEYS) : null;
        Integer label = hasHeader ? find(header, LABEL_KEYS) : null;
        Integer detail = hasHeader ? find(header, DETAIL_KEYS) : null;
        Integer amount = hasHeader ? find(header, AMOUNT_KEYS) : null;
        Integer debit = hasHeader ? find(header, DEBIT_KEYS) : null;
        Integer credit = hasHeader ? find(header, CREDIT_KEYS) : null;

        if (date == null) {
            date = bestColumn(data, columns, c -> datePattern(List.of(c)).isPresent(), -1, -1);
        }
        if (date == null) {
            date = 0;
        }
        if (amount == null && (debit == null || credit == null)) {
            amount = bestColumn(data, columns, c -> AmountText.parse(c).isPresent(), date, -1);
        }
        if (label == null) {
            label = longestTextColumn(data, columns, date, amount == null ? -1 : amount);
        }
        if (detail != null && detail.equals(label)) {
            detail = null;
        }
        final int dateCol = date;
        String pattern = datePattern(data.stream().limit(SAMPLE).map(r -> cell(r, dateCol)).toList()).orElse("dd/MM/yyyy");
        boolean debitCredit = debit != null && credit != null && (amount == null);
        return new CsvMapping(headerRows, date, pattern, label, detail,
                debitCredit ? CsvMapping.AmountMode.DEBIT_CREDIT : CsvMapping.AmountMode.SIGNED,
                debitCredit ? null : (amount == null ? Math.min(columns - 1, date + 2) : amount),
                debitCredit ? debit : null, debitCredit ? credit : null);
    }

    /** Premier format qui lit toutes les dates non vides de l'echantillon. */
    public static Optional<String> datePattern(List<String> samples) {
        List<String> values = samples.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (values.isEmpty()) {
            return Optional.empty();
        }
        for (String pattern : DATE_PATTERNS) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern.replace("yyyy", "uuuu").replace("yy", "uu"))
                    .withResolverStyle(ResolverStyle.STRICT);
            if (values.stream().allMatch(v -> parses(v, f))) {
                return Optional.of(pattern);
            }
        }
        return Optional.empty();
    }

    public static Optional<LocalDate> parseDate(String value, String pattern) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern.replace("yyyy", "uuuu").replace("yy", "uu"))
                .withResolverStyle(ResolverStyle.STRICT);
        try {
            return Optional.of(LocalDate.parse(value.strip(), f));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static boolean parses(String v, DateTimeFormatter f) {
        try {
            LocalDate.parse(v, f);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Integer find(List<String> header, String... names) {
        for (String name : names) {
            for (int i = 0; i < header.size(); i++) {
                if (normalizeHeader(header.get(i)).equals(name)) {
                    return i;
                }
            }
        }
        for (String name : names) {
            for (int i = 0; i < header.size(); i++) {
                if (normalizeHeader(header.get(i)).startsWith(name + " ")) {
                    return i;
                }
            }
        }
        return null;
    }

    static String normalizeHeader(String h) {
        return java.text.Normalizer.normalize(h.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static Integer bestColumn(List<List<String>> data, int columns,
                                      java.util.function.Predicate<String> test, int skipA, int skipB) {
        Integer best = null;
        long bestCount = 0;
        for (int c = 0; c < columns; c++) {
            if (c == skipA || c == skipB) {
                continue;
            }
            final int col = c;
            long count = data.stream().limit(SAMPLE).filter(r -> test.test(cell(r, col))).count();
            if (count > bestCount) {
                bestCount = count;
                best = c;
            }
        }
        return best;
    }

    private static int longestTextColumn(List<List<String>> data, int columns, int skipA, int skipB) {
        int best = 0;
        double bestLength = -1;
        for (int c = 0; c < columns; c++) {
            if (c == skipA || c == skipB) {
                continue;
            }
            final int col = c;
            double avg = data.stream().limit(SAMPLE)
                    .mapToInt(r -> LabelNormalizer.normalize(cell(r, col)).length()).average().orElse(0);
            if (avg > bestLength) {
                bestLength = avg;
                best = c;
            }
        }
        return best;
    }

    static String cell(List<String> row, int col) {
        return col >= 0 && col < row.size() ? row.get(col) : "";
    }
}
