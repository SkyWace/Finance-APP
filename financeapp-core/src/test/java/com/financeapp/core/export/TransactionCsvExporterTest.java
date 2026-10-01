package com.financeapp.core.export;

import com.financeapp.core.money.Money;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransactionCsvExporterTest {

    private static Transaction tx(long account, String date, String label, String amount, TransactionType type,
                                  Long category, String note, Long other) {
        return new Transaction(1L, account, LocalDate.parse(date), label, Money.eur(amount), type,
                TransactionStatus.COMPLETED, category, note, other == null ? null : "g1", other, null, null);
    }

    @Test
    void writesAFrenchSpreadsheetFriendlyFile() throws Exception {
        StringWriter out = new StringWriter();
        int n = TransactionCsvExporter.write(List.of(
                        tx(1, "2026-09-28", "Courses Carrefour", "-1234.5", TransactionType.EXPENSE, 10L, null, null),
                        tx(1, "2026-09-30", "Salaire", "1850", TransactionType.INCOME, null, "septembre", null),
                        tx(1, "2026-10-01", "Épargne", "-100", TransactionType.TRANSFER, null, null, 2L)),
                Map.of(1L, "Compte courant", 2L, "Livret A"), Map.of(10L, "Alimentation › Courses"), out);

        assertEquals(3, n);
        String[] lines = out.toString().split("\r\n");
        assertEquals('﻿', lines[0].charAt(0), "BOM pour les accents dans Excel");
        assertEquals("Date;Compte;Libellé;Catégorie;Type;Statut;Montant;Devise;Autre compte (virement);Commentaire;Étiquettes",
                lines[0].substring(1));
        assertEquals("28/09/2026;Compte courant;Courses Carrefour;Alimentation › Courses;Dépense;Effectué;-1234,50;EUR;;;",
                lines[1]);
        assertEquals("30/09/2026;Compte courant;Salaire;;Revenu;Effectué;1850,00;EUR;;septembre;", lines[2]);
        assertEquals("01/10/2026;Compte courant;Épargne;;Virement interne;Effectué;-100,00;EUR;Livret A;;", lines[3]);
    }

    @Test
    void quotesSeparatorsAndNeutralisesFormulas() throws Exception {
        StringWriter out = new StringWriter();
        TransactionCsvExporter.write(List.of(
                tx(1, "2026-09-28", "=HYPERLINK(\"http://x\")", "-5", TransactionType.EXPENSE, null,
                        "ligne 1\nligne 2; suite", null),
                tx(1, "2026-09-29", "+33 RESTO", "-9.99", TransactionType.EXPENSE, null, "@note", null)),
                Map.of(1L, "Compte ; perso"), Map.of(), out);

        String[] lines = out.toString().substring(1).split("\r\n");
        assertTrue(lines[1].startsWith("28/09/2026;\"Compte ; perso\";\"'=HYPERLINK(\"\"http://x\"\")\";"), lines[1]);
        assertTrue(lines[1].endsWith(";-5,00;EUR;;\"ligne 1\nligne 2; suite\";"), "montant negatif non prefixe");
        assertTrue(out.toString().contains("'+33 RESTO") && out.toString().contains("'@note"));
    }

    @Test
    void emptyExportStillHasTheHeader() throws Exception {
        StringWriter out = new StringWriter();
        assertEquals(0, TransactionCsvExporter.write(List.of(), Map.of(), Map.of(), out));
        assertTrue(out.toString().startsWith("﻿Date;Compte;"));
    }

    @Test
    void splitTransactionsDetailTheirCategoriesAndTagsAreListed() throws Exception {
        Transaction split = new Transaction(1L, 1L, LocalDate.parse("2026-09-28"), "Hypermarché", Money.eur("-120"),
                TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null, null, null, null, null,
                List.of(new com.financeapp.core.transaction.SplitLine(10L, Money.eur("-90")),
                        new com.financeapp.core.transaction.SplitLine(null, Money.eur("-30"))),
                java.util.Set.of(5L, 6L));
        StringWriter out = new StringWriter();
        TransactionCsvExporter.write(List.of(split), Map.of(1L, "Compte courant"), Map.of(10L, "Alimentation"),
                Map.of(5L, "vacances", 6L, "Remboursable"), out);
        assertEquals("28/09/2026;Compte courant;Hypermarché;Alimentation (90,00) + Sans catégorie (30,00);Dépense;"
                + "Effectué;-120,00;EUR;;;Remboursable, vacances", out.toString().substring(1).split("\r\n")[1]);
    }
}
