package com.financeapp.core.export;

import com.financeapp.core.transaction.Transaction;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Export des operations au format CSV lisible par Excel et LibreOffice en
 * francais : separateur point-virgule, virgule decimale, dates jj/mm/aaaa,
 * UTF-8 avec BOM (accents corrects a l'ouverture dans Excel).
 *
 * <p>Les champs texte commencant par {@code = + - @}, une tabulation ou un retour
 * chariot sont prefixes d'une apostrophe : un libelle importe ne peut pas etre
 * interprete comme une formule par le tableur (injection CSV). Les montants,
 * numeriques, ne sont pas concernes.
 */
public final class TransactionCsvExporter {

    public static final char SEPARATOR = ';';
    public static final List<String> HEADER = List.of("Date", "Compte", "Libellé", "Catégorie", "Type", "Statut",
            "Montant", "Devise", "Autre compte (virement)", "Commentaire");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final char BOM = '﻿';

    private TransactionCsvExporter() {
    }

    /**
     * @param accountNames  nom de chaque compte par identifiant
     * @param categoryNames nom complet de chaque categorie ("Logement › Loyer")
     * @return nombre d'operations ecrites
     */
    public static int write(List<Transaction> transactions, Map<Long, String> accountNames,
                            Map<Long, String> categoryNames, Writer out) throws IOException {
        out.write(BOM);
        line(out, HEADER);
        for (Transaction t : transactions) {
            line(out, List.of(
                    DATE.format(t.date()),
                    text(accountNames.getOrDefault(t.accountId(), "")),
                    text(t.label()),
                    text(t.categoryId() == null ? "" : categoryNames.getOrDefault(t.categoryId(), "")),
                    t.type().label(),
                    t.status().label(),
                    amount(t.amount().amount(), t.amount().currency().getDefaultFractionDigits()),
                    t.amount().currency().getCurrencyCode(),
                    text(t.transferAccountId() == null ? "" : accountNames.getOrDefault(t.transferAccountId(), "")),
                    text(t.note() == null ? "" : t.note())));
        }
        out.flush();
        return transactions.size();
    }

    /** Montant signe, virgule decimale, sans separateur de milliers ("-1234,50"). */
    static String amount(BigDecimal value, int digits) {
        return value.setScale(Math.max(0, digits), java.math.RoundingMode.HALF_EVEN).toPlainString().replace('.', ',');
    }

    /** Neutralise un debut de formule de tableur. */
    static String text(String value) {
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    private static void line(Writer out, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(SEPARATOR);
            }
            out.write(quote(cells.get(i)));
        }
        out.write("\r\n");
    }

    private static String quote(String cell) {
        if (cell.indexOf(SEPARATOR) < 0 && cell.indexOf('"') < 0 && cell.indexOf('\n') < 0 && cell.indexOf('\r') < 0) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }
}
