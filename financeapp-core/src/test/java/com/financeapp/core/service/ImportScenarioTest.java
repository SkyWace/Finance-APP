package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.available.AvailableBalanceResult.SectionKind;
import com.financeapp.core.available.HorizonType;
import com.financeapp.core.categorization.CategorizationRule;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.imports.CsvTable;
import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportCandidate;
import com.financeapp.core.imports.ImportedRow;
import com.financeapp.core.money.Money;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Import bout a bout : fichier → apercu → import → disponible reel → Inbox → regles → annulation. */
class ImportScenarioTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private final TestApp app = new TestApp(TODAY);
    private Account checking;
    private Category groceries;
    private Category fuel;

    private static final String CSV = """
            Date;Libellé;Débit;Crédit
            03/10/2026;PRLV SEPA LOYER OCTOBRE;650,00;
            04/10/2026;CB CARREFOUR MARKET 1234;72,41;
            05/10/2026;CB TOTAL ACCESS;81,20;
            05/10/2026;CB AMAZON EU;34,99;
            """;

    @BeforeEach
    void setUp() {
        checking = app.account("Compte courant", AccountType.CHECKING, "2000");
        Category food = app.categories.create(null, "Alimentation", CategoryKind.EXPENSE);
        groceries = app.categories.create(food.id(), "Courses", null);
        Category transport = app.categories.create(null, "Transport", CategoryKind.EXPENSE);
        fuel = app.categories.create(transport.id(), "Carburant", null);
        // Loyer recurrent le 3 (categorie Logement), deja suivi avant l'import
        Category housing = app.categories.create(null, "Logement", CategoryKind.EXPENSE);
        app.store.rules.save(new com.financeapp.core.recurring.RecurringRule(null, checking.id(), null,
                TransactionType.EXPENSE, "Loyer", Money.eur("650"), housing.id(), com.financeapp.core.recurring.Frequency.MONTHLY,
                1, LocalDate.of(2026, 1, 3), null, LocalDate.of(2026, 1, 3), true, true, null));
        app.categorization.save(new CategorizationRule(null, "TOTAL", fuel.id(), TransactionType.EXPENSE, true));
    }

    private List<ImportCandidate> preview() {
        CsvTable table = app.imports.readCsv(CSV.getBytes(StandardCharsets.UTF_8));
        List<ImportedRow> rows = app.imports.convertCsv(table, app.imports.guessMapping(table));
        return app.imports.plan(checking.id(), rows);
    }

    private ImportBatch importAll(List<ImportCandidate> candidates) {
        return app.imports.commit(checking.id(), "releve.csv", ImportService.Format.CSV, candidates.stream()
                .map(c -> new ImportService.Decision(c, c.includedByDefault(),
                        c.suggestion() == null ? null : c.suggestion().categoryId()))
                .toList());
    }

    @Test
    void importedRentRealizesTheRecurringOccurrenceInsteadOfBeingCountedTwice() {
        var before = app.available.compute(HorizonType.END_OF_MONTH, null);
        assertEquals(Money.eur("-650"), before.total(SectionKind.PLANNED_EXPENSES), "loyer du 3 en retard, a venir");

        List<ImportCandidate> candidates = preview();
        assertEquals(ImportCandidate.Kind.MATCHES_RECURRING, candidates.get(0).kind());
        assertEquals(fuel.id(), candidates.get(2).suggestion().categoryId(), "regle TOTAL");
        ImportBatch batch = importAll(candidates);

        assertEquals(3, batch.created(), "le loyer n'est pas une operation nouvelle");
        assertEquals(1, batch.reconciled(), "l'echeance du loyer est realisee");
        assertEquals(Money.eur("1161.40"), app.accounts.balanceOf(checking.id()));
        var after = app.available.compute(HorizonType.END_OF_MONTH, null);
        assertTrue(after.section(SectionKind.PLANNED_EXPENSES).isEmpty(), "le loyer n'est plus a venir");
        assertEquals(before.available().minus(Money.eur("188.60")), after.available(),
                "seules les depenses non prevues changent le disponible");
    }

    @Test
    void reimportingTheSameFileCreatesNothing() {
        importAll(preview());
        List<ImportCandidate> again = preview();
        assertTrue(again.stream().allMatch(c -> c.kind() == ImportCandidate.Kind.DUPLICATE), again.toString());
        ImportBatch second = importAll(again);
        assertEquals(0, second.created());
        assertEquals(4, second.skipped());
    }

    @Test
    void plannedTransactionIsRealizedAndRestoredOnUndo() {
        Transaction planned = app.expense(checking, LocalDate.of(2026, 10, 7), "Plein prévu", "81.20", TransactionStatus.PLANNED);
        List<ImportCandidate> candidates = preview();
        assertEquals(ImportCandidate.Kind.MATCHES_PLANNED, candidates.get(2).kind());
        ImportBatch batch = importAll(candidates);
        assertEquals(2, batch.reconciled(), "operation prevue + echeance du loyer");
        assertEquals(2, batch.created());
        Transaction realized = app.transactions.get(planned.id());
        assertEquals(TransactionStatus.COMPLETED, realized.status());
        assertEquals(LocalDate.of(2026, 10, 5), realized.date());

        app.imports.undo(batch.id());

        Transaction restored = app.transactions.get(planned.id());
        assertEquals(TransactionStatus.PLANNED, restored.status());
        assertEquals(LocalDate.of(2026, 10, 7), restored.date());
        assertEquals(Money.eur("2000"), app.accounts.balanceOf(checking.id()));
        assertEquals(0, app.inbox.count());
        assertTrue(app.imports.batches().getFirst().undone());
        assertEquals(1, app.recurring.pendingOccurrences(TODAY.minusDays(14), TODAY).size(), "le loyer redevient a venir");
    }

    @Test
    void inboxValidationAndRuleProposal() {
        importAll(preview());
        assertEquals(4, app.inbox.count());

        var items = app.inbox.items();
        var amazon = items.stream().filter(i -> i.transaction().label().contains("AMAZON")).findFirst().orElseThrow();
        assertNull(amazon.proposedCategoryId());
        var carrefour = items.stream().filter(i -> i.transaction().label().contains("CARREFOUR")).findFirst().orElseThrow();

        // Correction manuelle : Carrefour → Courses, puis proposition de regle
        app.inbox.validate(carrefour.transaction().id(), groceries.id());
        assertEquals(groceries.id(), app.transactions.get(carrefour.transaction().id()).categoryId());
        String pattern = app.categorization.ruleProposal("CB CARREFOUR MARKET 1234", TransactionType.EXPENSE, groceries.id())
                .orElseThrow();
        assertEquals("CARREFOUR", pattern);
        app.categorization.save(new CategorizationRule(null, pattern, groceries.id(), null, true));
        assertTrue(app.categorization.ruleProposal("CB CARREFOUR CITY", TransactionType.EXPENSE, groceries.id()).isEmpty(),
                "deja couvert par une regle");

        var rent = items.stream().filter(i -> i.transaction().label().contains("LOYER")).findFirst().orElseThrow();
        assertNotNull(rent.proposedCategoryId(), "la categorie de l'echeance recurrente est reprise");
        assertEquals(2, app.inbox.validateAllWithCategory(), "loyer et Total ont une categorie, pas Amazon");
        assertEquals(1, app.inbox.count());
        assertEquals(1, app.inbox.items().size());
    }

    @Test
    void rulesCanBeAppliedToExistingUncategorizedOperations() {
        app.expense(checking, TODAY.minusDays(40), "CB TOTAL ACCESS 999", "60", TransactionStatus.COMPLETED);
        app.expense(checking, TODAY.minusDays(30), "Boulangerie", "5", TransactionStatus.COMPLETED);
        app.expense(checking, TODAY.minusDays(20), "TOTAL deja classe", "40", groceries.id());

        assertEquals(1, app.categorization.applyToUncategorized());
        assertThrows(BusinessException.class, () -> app.categorization.save(
                new CategorizationRule(null, "total", fuel.id(), TransactionType.EXPENSE, true)), "doublon de regle");
        assertEquals(groceries.id(), app.transactions.search(com.financeapp.core.port.TransactionQuery.all()).stream()
                .filter(t -> t.label().equals("TOTAL deja classe")).findFirst().orElseThrow().categoryId(),
                "une categorie existante n'est jamais ecrasee");
    }

    @Test
    void merchantsAndCustomPeriodComparison() {
        importAll(preview());
        var merchants = app.statistics.merchants(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 10);
        assertEquals("PRLV SEPA LOYER OCTOBRE", merchants.getFirst().label());
        assertEquals(1, merchants.getFirst().count());

        var cmp = app.statistics.comparePeriods(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                YearMonth.of(2026, 10).atDay(1), YearMonth.of(2026, 10).atEndOfMonth());
        assertEquals(Money.eur("838.60"), cmp.total().current());
        assertNull(cmp.total().percentChange());
        assertThrows(BusinessException.class, () -> app.statistics.comparePeriods(TODAY, TODAY.minusDays(1), TODAY, TODAY));
    }
}
