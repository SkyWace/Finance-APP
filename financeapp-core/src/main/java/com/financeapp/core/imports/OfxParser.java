package com.financeapp.core.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture des operations d'un releve OFX (versions 1.x SGML, sans balises
 * fermantes, et 2.x XML). Seuls les blocs {@code <STMTTRN>} sont exploites :
 * date ({@code DTPOSTED}), montant signe ({@code TRNAMT}), libelle
 * ({@code NAME} + {@code MEMO}) et identifiant bancaire ({@code FITID}),
 * qui permet une detection de doublons fiable.
 */
public final class OfxParser {

    private static final Pattern TRANSACTION = Pattern.compile("<STMTTRN>(.*?)(</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public List<ImportedRow> parse(String content) {
        List<ImportedRow> rows = new ArrayList<>();
        Matcher m = TRANSACTION.matcher(content);
        int index = 0;
        while (m.find()) {
            index++;
            String block = m.group(1);
            String name = tag(block, "NAME").orElse("");
            String memo = tag(block, "MEMO").orElse("");
            String label = (name + (memo.isBlank() || memo.equalsIgnoreCase(name) ? "" : " " + memo)).strip();
            Optional<LocalDate> date = tag(block, "DTPOSTED").flatMap(OfxParser::parseDate);
            Optional<BigDecimal> amount = tag(block, "TRNAMT").flatMap(AmountText::parse);
            if (date.isEmpty()) {
                rows.add(ImportedRow.invalid(index, label, "Date absente ou illisible"));
            } else if (amount.isEmpty() || amount.get().signum() == 0) {
                rows.add(ImportedRow.invalid(index, label, "Montant absent ou nul"));
            } else if (label.isBlank()) {
                rows.add(ImportedRow.invalid(index, label, "Libellé vide"));
            } else {
                rows.add(new ImportedRow(index, date.get(), label, amount.get(), tag(block, "FITID").orElse(null), null));
            }
        }
        return rows;
    }

    /** Valeur d'une balise, qu'elle soit fermee (XML) ou non (SGML). */
    static Optional<String> tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(block);
        if (!m.find()) {
            return Optional.empty();
        }
        String value = m.group(1).strip()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"");
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** {@code 20260928}, {@code 20260928120000}, {@code 20260928120000.000[+2:CEST]}. */
    static Optional<LocalDate> parseDate(String value) {
        if (value.length() < 8) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
