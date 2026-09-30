package com.financeapp.core.imports;

import com.financeapp.core.categorization.CategorizationEngine;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ImportPlannerTest {

    private static final long ACCOUNT = 1;
    private final ImportPlanner planner = new ImportPlanner();
    private final CategorizationEngine noRules = new CategorizationEngine(List.of(), List.of());

    private static ImportedRow row(String date, String label, String amount) {
        return new ImportedRow(1, LocalDate.parse(date), label, new BigDecimal(amount), null, null);
    }

    private static Transaction existing(String date, String label, String amount, TransactionStatus status) {
        Money m = Money.eur(amount);
        return new Transaction(10L, ACCOUNT, LocalDate.parse(date), label, m,
                m.isNegative() ? TransactionType.EXPENSE : TransactionType.INCOME, status, null, null, null, null, null, null);
    }

    private List<ImportCandidate> plan(List<ImportedRow> rows, List<Transaction> existing, Set<String> ids, List<PlannedItem> pending) {
        return planner.plan(rows, ACCOUNT, Money.EUR, existing, ids, pending, noRules);
    }

    @Test
    void newRowsAreIncludedByDefault() {
        ImportCandidate c = plan(List.of(row("2026-09-26", "STATION TOTAL", "-56.42")), List.of(), Set.of(), List.of()).getFirst();
        assertEquals(ImportCandidate.Kind.NEW, c.kind());
        assertTrue(c.includedByDefault());
    }

    @Test
    void exactAndPossibleDuplicatesAreExcludedByDefault() {
        List<Transaction> existing = List.of(
                existing("2026-09-24", "Carrefour Market", "-55.33", TransactionStatus.COMPLETED),
                existing("2026-09-20", "Resto avec Paul", "-38.50", TransactionStatus.COMPLETED));
        List<ImportCandidate> result = plan(List.of(
                row("2026-09-25", "CB CARREFOUR MARKET 1234", "-55.33"),
                row("2026-09-21", "LE COMPTOIR", "-38.50"),
                row("2026-09-22", "LE COMPTOIR", "-38.50")), existing, Set.of(), List.of());

        assertEquals(ImportCandidate.Kind.DUPLICATE, result.get(0).kind(), "meme montant, libelle equivalent, 1 jour d'ecart");
        assertEquals(ImportCandidate.Kind.POSSIBLE_DUPLICATE, result.get(1).kind(), "meme montant, libelle different");
        assertEquals(ImportCandidate.Kind.NEW, result.get(2).kind(), "chaque operation existante ne sert qu'une fois");
        assertFalse(result.get(0).includedByDefault());
        assertFalse(result.get(1).includedByDefault());
        assertSame(existing.get(1), result.get(1).matchedExisting());
    }

    @Test
    void sameAmountFarApartIsNotADuplicate() {
        List<ImportCandidate> result = plan(List.of(row("2026-09-25", "CARREFOUR", "-55.33")),
                List.of(existing("2026-09-10", "Carrefour", "-55.33", TransactionStatus.COMPLETED)), Set.of(), List.of());
        assertEquals(ImportCandidate.Kind.NEW, result.getFirst().kind());
    }

    @Test
    void identicalLinesInTheSameFileNeedAConfirmation() {
        List<ImportCandidate> result = plan(List.of(
                row("2026-09-25", "CAFE DU COIN", "-2.50"),
                row("2026-09-25", "CAFE DU COIN", "-2.50")), List.of(), Set.of(), List.of());
        assertEquals(ImportCandidate.Kind.NEW, result.get(0).kind());
        assertEquals(ImportCandidate.Kind.DUPLICATE_IN_FILE, result.get(1).kind());
        assertFalse(result.get(1).includedByDefault());
    }

    @Test
    void knownBankIdentifierIsADuplicate() {
        ImportedRow ofx = new ImportedRow(1, LocalDate.of(2026, 9, 25), "X", new BigDecimal("-1"), "FIT-1", null);
        assertEquals(ImportCandidate.Kind.DUPLICATE, plan(List.of(ofx), List.of(), Set.of("FIT-1"), List.of()).getFirst().kind());
    }

    @Test
    void plannedOperationsAreNeverDuplicatesButAreRealized() {
        Transaction plannedTx = existing("2026-10-05", "Contrôle technique", "-78", TransactionStatus.PLANNED);
        PlannedItem planned = new PlannedItem(plannedTx.date(), ACCOUNT, plannedTx.label(), plannedTx.amount(),
                TransactionType.EXPENSE, 7L, PlannedItem.Source.PLANNED_TRANSACTION, 10L, null, null, true);
        ImportCandidate c = plan(List.of(row("2026-10-03", "AUTOSUR 69", "-78.00")), List.of(plannedTx), Set.of(),
                List.of(planned)).getFirst();
        assertEquals(ImportCandidate.Kind.MATCHES_PLANNED, c.kind());
        assertTrue(c.includedByDefault());
        assertEquals(7L, c.suggestion().categoryId(), "la categorie de l'operation prevue est reprise");
    }

    @Test
    void matchingLabelWidensTheDateWindow() {
        PlannedItem planned = new PlannedItem(LocalDate.of(2026, 10, 5), ACCOUNT, "Contrôle technique", Money.eur("-78"),
                TransactionType.EXPENSE, null, PlannedItem.Source.PLANNED_TRANSACTION, 10L, null, null, true);
        List<ImportCandidate> result = plan(List.of(
                row("2026-09-27", "AUTOSUR 69", "-78.00"),
                row("2026-09-27", "AUTOSUR CONTROLE TECHNIQUE", "-78.00")), List.of(), Set.of(), List.of(planned));
        assertEquals(ImportCandidate.Kind.NEW, result.get(0).kind(), "8 jours d'ecart sans libelle commun");
        assertEquals(ImportCandidate.Kind.MATCHES_PLANNED, result.get(1).kind(), "le libelle confirme : jusqu'a 10 jours");
    }

    @Test
    void recurringOccurrenceMatchesWithinTolerance() {
        PlannedItem electricity = new PlannedItem(LocalDate.of(2026, 10, 8), ACCOUNT, "Électricité", Money.eur("-80"),
                TransactionType.EXPENSE, null, PlannedItem.Source.RECURRING, null, 42L, null, true);
        PlannedItem salary = new PlannedItem(LocalDate.of(2026, 9, 28), ACCOUNT, "Salaire", Money.eur("1850"),
                TransactionType.INCOME, null, PlannedItem.Source.RECURRING, null, 43L, null, true);

        List<ImportCandidate> result = plan(List.of(
                row("2026-10-09", "PRLV EDF", "-84.12"),
                row("2026-09-29", "VIR SALAIRE", "1850.00"),
                row("2026-10-09", "PRLV AUTRE", "-95.00")), List.of(), Set.of(), List.of(electricity, salary));

        assertEquals(ImportCandidate.Kind.MATCHES_RECURRING, result.get(0).kind(), "facture variable : +5 %");
        assertEquals(42L, result.get(0).matchedPlanned().recurringId());
        assertEquals(ImportCandidate.Kind.MATCHES_RECURRING, result.get(1).kind());
        assertEquals(ImportCandidate.Kind.NEW, result.get(2).kind(), "l'echeance n'est rapprochee qu'une fois, et +19 % sort de la tolerance");
    }

    @Test
    void invalidRowsCannotBeImported() {
        ImportCandidate c = plan(List.of(ImportedRow.invalid(4, "x", "Date illisible")), List.of(), Set.of(), List.of()).getFirst();
        assertEquals(ImportCandidate.Kind.INVALID, c.kind());
        assertFalse(c.includedByDefault());
    }
}
