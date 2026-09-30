package com.financeapp.core.imports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileParsingTest {

    private final CsvReader reader = new CsvReader();
    private final CsvMappingGuesser guesser = new CsvMappingGuesser();
    private final CsvRowConverter converter = new CsvRowConverter();

    @Test
    void amounts() {
        assertEquals(new BigDecimal("1234.56"), AmountText.parse("1 234,56").orElseThrow());
        assertEquals(new BigDecimal("1234.56"), AmountText.parse("1 234,56 €").orElseThrow());
        assertEquals(new BigDecimal("-12.50"), AmountText.parse("-12.50").orElseThrow());
        assertEquals(new BigDecimal("1234.56"), AmountText.parse("1.234,56").orElseThrow());
        assertEquals(new BigDecimal("1234.56"), AmountText.parse("1,234.56").orElseThrow());
        assertEquals(new BigDecimal("-12.00"), AmountText.parse("(12,00)").orElseThrow());
        assertEquals(new BigDecimal("-12.5"), AmountText.parse("12,5-").orElseThrow());
        assertEquals(new BigDecimal("5"), AmountText.parse("+5").orElseThrow());
        assertEquals(new BigDecimal("1234567"), AmountText.parse("1.234.567").orElseThrow());
        assertTrue(AmountText.parse("abc").isEmpty());
        assertTrue(AmountText.parse("").isEmpty());
        assertTrue(AmountText.parse("12,34,56,7").isPresent(), "virgules de milliers");
    }

    /** Export type "banque de detail" : preambule, separateur ;, colonnes Debit / Credit, Windows-1252. */
    @Test
    void frenchBankCsvWithPreambleAndDebitCredit() {
        String csv = """
                Téléchargement du 29/09/2026;;;
                Compte courant n° 12345;;;
                Date;Libellé;Débit euros;Crédit euros
                28/09/2026;"VIREMENT SALAIRE SEPTEMBRE";;1 850,00
                26/09/2026;CB STATION TOTAL 25/09;56,42;
                24/09/2026;"CB CARREFOUR MARKET; LYON";55,33;
                """;
        CsvTable table = reader.read(csv.getBytes(Charset.forName("windows-1252")));
        assertEquals(';', table.delimiter());
        assertEquals("windows-1252", table.charset());

        CsvMapping m = guesser.guess(table);
        assertEquals(3, m.headerRows());
        assertEquals(0, m.dateColumn());
        assertEquals(1, m.labelColumn());
        assertEquals(CsvMapping.AmountMode.DEBIT_CREDIT, m.mode());
        assertEquals("dd/MM/yyyy", m.datePattern());

        List<ImportedRow> rows = converter.convert(table, m);
        assertEquals(3, rows.size());
        assertEquals(new BigDecimal("1850.00"), rows.get(0).amount());
        assertEquals(new BigDecimal("-56.42"), rows.get(1).amount());
        assertEquals("CB CARREFOUR MARKET; LYON", rows.get(2).label(), "separateur entre guillemets");
        assertEquals(LocalDate.of(2026, 9, 24), rows.get(2).date());
    }

    /** Export "banque en ligne" : UTF-8 avec BOM, dates ISO, montant signe, colonnes supplementaires. */
    @Test
    void onlineBankCsvWithSignedAmount() {
        String csv = "﻿dateOp,dateVal,label,category,supplierFound,amount,comment\n"
                + "2026-09-24,2026-09-25,CARTE 23/09/26 NETFLIX.COM,Loisirs,netflix,-22.99,\n"
                + "2026-09-20,2026-09-21,\"VIR SEPA DE M. DUPONT, remboursement\",Virements,,40.00,\"merci \"\"Paul\"\"\"\n";
        CsvTable table = reader.read(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(',', table.delimiter());
        CsvMapping m = guesser.guess(table);
        assertEquals(1, m.headerRows());
        assertEquals("yyyy-MM-dd", m.datePattern());
        assertEquals(CsvMapping.AmountMode.SIGNED, m.mode());
        assertEquals(5, m.amountColumn());
        assertEquals(2, m.labelColumn());

        List<ImportedRow> rows = converter.convert(table, m);
        assertEquals(new BigDecimal("-22.99"), rows.get(0).amount());
        assertEquals("VIR SEPA DE M. DUPONT, remboursement", rows.get(1).label());
        assertEquals("merci \"Paul\"", table.cell(2, 6));
    }

    @Test
    void headerlessCsvIsGuessedFromContent() {
        String csv = "03/09/2026\tLOYER SEPTEMBRE\t-650,00\n05/09/2026\tASSURANCE AUTO\t-101,00\n";
        CsvTable table = reader.read(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals('\t', table.delimiter());
        CsvMapping m = guesser.guess(table);
        assertEquals(0, m.headerRows());
        assertEquals(1, m.labelColumn());
        assertEquals(2, m.amountColumn());
        assertEquals(2, converter.convert(table, m).size());
    }

    @Test
    void invalidLinesAreReportedNotDropped() {
        String csv = "Date;Libellé;Montant\n31/02/2026;Date impossible;-10\n01/09/2026;Montant vide;\n02/09/2026;OK;-5\n";
        CsvTable table = reader.read(csv.getBytes(StandardCharsets.UTF_8));
        List<ImportedRow> rows = converter.convert(table, guesser.guess(table));
        assertEquals(3, rows.size());
        assertFalse(rows.get(0).isValid());
        assertTrue(rows.get(0).error().contains("Date"));
        assertFalse(rows.get(1).isValid());
        assertTrue(rows.get(2).isValid());
        assertEquals(3, rows.get(1).line());
    }

    @Test
    void ofxSgmlAndXml() {
        String sgml = """
                OFXHEADER:100
                DATA:OFXSGML
                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260926120000.000[+2:CEST]<TRNAMT>-56.42<FITID>ABC123<NAME>CB STATION TOTAL<MEMO>25/09
                <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20260928<TRNAMT>1850.00<FITID>ABC124<NAME>SALAIRE
                </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
                """;
        List<ImportedRow> rows = new OfxParser().parse(sgml);
        assertEquals(2, rows.size());
        assertEquals(LocalDate.of(2026, 9, 26), rows.get(0).date());
        assertEquals(new BigDecimal("-56.42"), rows.get(0).amount());
        assertEquals("ABC123", rows.get(0).externalId());
        assertEquals("CB STATION TOTAL 25/09", rows.get(0).label());

        String xml = "<OFX><BANKTRANLIST><STMTTRN><DTPOSTED>20260905</DTPOSTED><TRNAMT>-22,99</TRNAMT>"
                + "<FITID>X1</FITID><NAME>NETFLIX &amp; CO</NAME></STMTTRN></BANKTRANLIST></OFX>";
        ImportedRow r = new OfxParser().parse(xml).getFirst();
        assertEquals(new BigDecimal("-22.99"), r.amount());
        assertEquals("NETFLIX & CO", r.label());
    }

    @Test
    void qif() {
        String qif = """
                !Type:Bank
                D28/09/2026
                T1 850,00
                PSALAIRE SEPTEMBRE
                ^
                D9/26'26
                T-56.42
                PSTATION TOTAL
                MCarburant
                ^
                """;
        List<ImportedRow> rows = new QifParser().parse(qif);
        assertEquals(2, rows.size());
        assertEquals(new BigDecimal("1850.00"), rows.get(0).amount());
        assertEquals(LocalDate.of(2026, 9, 26), rows.get(1).date());
        assertEquals("STATION TOTAL Carburant", rows.get(1).label());
    }
}
