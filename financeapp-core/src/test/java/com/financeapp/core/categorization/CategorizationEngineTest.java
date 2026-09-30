package com.financeapp.core.categorization;

import com.financeapp.core.money.Money;
import com.financeapp.core.text.LabelNormalizer;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CategorizationEngineTest {

    private static final long FUEL = 1;
    private static final long GROCERIES = 2;
    private static final long DRIVE = 3;
    private static final long STREAMING = 4;
    private static final long RESTAURANT = 5;

    private static Transaction tx(String label, Long category) {
        return new Transaction(1L, 1, LocalDate.of(2026, 9, 1), label, Money.eur("-10"), TransactionType.EXPENSE,
                TransactionStatus.COMPLETED, category, null, null, null, null, null);
    }

    @Test
    void normalization() {
        assertEquals("carrefour market lyon", LabelNormalizer.normalize("CB CARREFOUR MARKET 1234 LYON 25/09"));
        assertEquals("netflix com", LabelNormalizer.normalize("PRLV SEPA NETFLIX.COM"));
        assertEquals("carrefour", LabelNormalizer.keyword("CB CARREFOUR MARKET 1234"));
        assertEquals("total", LabelNormalizer.keyword("PAIEMENT PAR CARTE TOTAL ACCESS"));
        assertTrue(LabelNormalizer.containsWords("station total access", "total"));
        assertFalse(LabelNormalizer.containsWords("totalement gratuit", "total"), "mots entiers uniquement");
        assertEquals("loyer", LabelNormalizer.normalize("PRLV SEPA LOYER OCTOBRE"), "les noms de mois sont ignores");
        assertEquals("loyer", LabelNormalizer.normalize("Loyer août"));
    }

    /** Regles du brief : TOTAL → Carburant, CARREFOUR → Courses, NETFLIX → Streaming. */
    @Test
    void rulesOfTheBrief() {
        CategorizationEngine engine = new CategorizationEngine(List.of(
                new CategorizationRule(1L, "TOTAL", FUEL, TransactionType.EXPENSE, true),
                new CategorizationRule(2L, "Carrefour", GROCERIES, null, true),
                new CategorizationRule(3L, "carrefour drive", DRIVE, null, true),
                new CategorizationRule(4L, "Netflix", STREAMING, null, true),
                new CategorizationRule(5L, "inactive", RESTAURANT, null, false)), List.of());

        assertEquals(FUEL, engine.suggest("CB STATION TOTAL ACCESS 25/09", TransactionType.EXPENSE).orElseThrow().categoryId());
        assertTrue(engine.suggest("REMBOURSEMENT TOTAL", TransactionType.INCOME).isEmpty(), "regle limitee aux depenses");
        assertEquals(GROCERIES, engine.suggest("CB CARREFOUR MARKET", TransactionType.EXPENSE).orElseThrow().categoryId());
        assertEquals(DRIVE, engine.suggest("CARREFOUR DRIVE 1234", TransactionType.EXPENSE).orElseThrow().categoryId(),
                "le motif le plus specifique l'emporte");
        assertEquals(STREAMING, engine.suggest("PRLV SEPA NETFLIX.COM", TransactionType.EXPENSE).orElseThrow().categoryId());
        assertTrue(engine.suggest("RESTO INACTIVE", TransactionType.EXPENSE).isEmpty());
        assertTrue(engine.suggest("Virement", TransactionType.TRANSFER).isEmpty());
        assertEquals(CategorySuggestion.Source.RULE, engine.suggest("TOTAL", TransactionType.EXPENSE).orElseThrow().source());
    }

    @Test
    void learnsFromHistoryOnlyWhenConsistent() {
        CategorizationEngine engine = new CategorizationEngine(List.of(), List.of(
                tx("LE COMPTOIR 12/09", RESTAURANT), tx("LE COMPTOIR 02/08", RESTAURANT), tx("LE COMPTOIR", RESTAURANT),
                tx("AMAZON", GROCERIES), tx("AMAZON", FUEL),
                tx("BOULANGERIE", GROCERIES)));

        CategorySuggestion s = engine.suggest("LE COMPTOIR 29/09", TransactionType.EXPENSE).orElseThrow();
        assertEquals(RESTAURANT, s.categoryId());
        assertEquals(CategorySuggestion.Source.HISTORY, s.source());
        assertTrue(engine.suggest("AMAZON", TransactionType.EXPENSE).isEmpty(), "historique contradictoire");
        assertTrue(engine.suggest("BOULANGERIE", TransactionType.EXPENSE).isEmpty(), "une seule occurrence");
    }

    @Test
    void ruleNeedsLetters() {
        assertThrows(IllegalArgumentException.class, () -> new CategorizationRule(null, "1234", FUEL, null, true));
    }
}
