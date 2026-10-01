package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.budget.Budget;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Ventilation d'une operation sur plusieurs categories, et etiquettes. Aujourd'hui : 15/10/2026. */
class SplitsAndTagsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private final TestApp app = new TestApp(TODAY);
    private Account checking;
    private Category food;
    private Category groceries;
    private Category home;

    @BeforeEach
    void setUp() {
        checking = app.account("Compte courant", AccountType.CHECKING, "2000");
        food = app.categories.create(null, "Alimentation", CategoryKind.EXPENSE);
        groceries = app.categories.create(food.id(), "Courses", CategoryKind.EXPENSE);
        home = app.categories.create(null, "Maison", CategoryKind.EXPENSE);
    }

    private Transaction hypermarket(String date, TransactionStatus status, Set<Long> tags) {
        return app.transactions.create(new TransactionDraft(checking.id(), LocalDate.parse(date), "Hypermarché",
                new BigDecimal("120"), TransactionType.EXPENSE, status, null, null,
                List.of(new TransactionDraft.Split(groceries.id(), new BigDecimal("90")),
                        new TransactionDraft.Split(home.id(), new BigDecimal("30"))), tags));
    }

    @Test
    void aSplitExpenseKeepsOneBalanceMovementAndOneShareperCategory() {
        Transaction t = hypermarket("2026-10-05", TransactionStatus.COMPLETED, Set.of());
        assertTrue(t.isSplit());
        assertNull(t.categoryId(), "la categorie est portee par les lignes");
        assertEquals(Money.eur("-120"), t.amount());
        assertEquals(List.of(Money.eur("-90"), Money.eur("-30")), t.splits().stream().map(l -> l.amount()).toList());
        assertEquals(Money.eur("1880"), app.accounts.balanceOf(checking.id()), "un seul mouvement sur le compte");

        Transaction completedLater = app.transactions.get(t.id());
        app.transactions.setStatus(t.id(), TransactionStatus.PENDING);
        assertEquals(completedLater.splits(), app.transactions.get(t.id()).splits(), "le changement de statut garde la ventilation");
    }

    @Test
    void theSplitMustMatchTheAmountExactly() {
        BusinessException gap = assertThrows(BusinessException.class, () -> app.transactions.create(new TransactionDraft(
                checking.id(), TODAY, "Hypermarché", new BigDecimal("120"), TransactionType.EXPENSE,
                TransactionStatus.COMPLETED, null, null,
                List.of(new TransactionDraft.Split(groceries.id(), new BigDecimal("90")),
                        new TransactionDraft.Split(home.id(), new BigDecimal("20"))), Set.of())));
        assertTrue(gap.getMessage().contains("reste 10,00 à répartir"), gap.getMessage());

        assertThrows(BusinessException.class, () -> app.transactions.create(new TransactionDraft(checking.id(), TODAY,
                "X", new BigDecimal("10"), TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null,
                List.of(new TransactionDraft.Split(home.id(), new BigDecimal("10"))), Set.of())), "une seule ligne");
        assertThrows(BusinessException.class, () -> app.transactions.create(new TransactionDraft(checking.id(), TODAY,
                "X", new BigDecimal("10"), TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null,
                List.of(new TransactionDraft.Split(home.id(), new BigDecimal("12")),
                        new TransactionDraft.Split(food.id(), new BigDecimal("-2"))), Set.of())), "ligne negative");
    }

    @Test
    void analysesAndBudgetsCountEachShareInItsCategory() {
        hypermarket("2026-10-05", TransactionStatus.COMPLETED, Set.of());
        app.expense(checking, LocalDate.of(2026, 10, 6), "Boulangerie", "10", groceries.id());
        app.budgets.save(new Budget(null, food.id(), Money.eur("300"), false, true));
        app.budgets.save(new Budget(null, home.id(), Money.eur("50"), false, true));

        var report = app.statistics.report(YearMonth.of(2026, 10), YearMonth.of(2026, 9), 3);
        assertEquals(Money.eur("100"), report.categories().stream().filter(c -> food.id().equals(c.categoryId()))
                .findFirst().orElseThrow().amount(), "90 ventiles + 10, sous-categorie comprise");
        assertEquals(Money.eur("30"), report.categories().stream().filter(c -> home.id().equals(c.categoryId()))
                .findFirst().orElseThrow().amount());
        assertEquals(Money.eur("-130"), report.months().getLast().expenses(), "le total n'est compte qu'une fois");

        var budgets = app.budgets.progress(YearMonth.of(2026, 10));
        assertEquals(Money.eur("100"), budgets.stream().filter(b -> b.budget().categoryId() == food.id())
                .findFirst().orElseThrow().spent());
        assertEquals(Money.eur("30"), budgets.stream().filter(b -> b.budget().categoryId() == home.id())
                .findFirst().orElseThrow().spent());
    }

    @Test
    void aPlannedSplitExpenseReservesOnlyEachSharesInBudgets() {
        hypermarket("2026-10-25", TransactionStatus.PLANNED, Set.of());
        app.budgets.save(new Budget(null, home.id(), Money.eur("50"), false, true));
        var progress = app.budgets.progress(YearMonth.of(2026, 10)).getFirst();
        assertEquals(Money.eur("0"), progress.spent());
        assertEquals(Money.eur("30"), progress.planned(), "seulement la part Maison de l'operation prevue");
    }

    @Test
    void searchingACategoryFindsSplitOperationsAndTotalsOnlyTheirShare() {
        hypermarket("2026-10-05", TransactionStatus.COMPLETED, Set.of());
        app.expense(checking, LocalDate.of(2026, 10, 6), "Peinture", "45", home.id());
        TransactionQuery byHome = new TransactionQuery(null, null, null, null, home.id(), null, 100, 0);
        assertEquals(2, app.transactions.search(byHome).size());
        assertEquals(Money.eur("-75"), app.transactions.summarize(byHome, checking.currency()).expenses(), "30 + 45");
    }

    @Test
    void rulesNeverOverwriteASplitAndUsedCategoriesCannotBeDeleted() {
        Transaction t = hypermarket("2026-10-05", TransactionStatus.COMPLETED, Set.of());
        app.categorization.save(new com.financeapp.core.categorization.CategorizationRule(null, "Hypermarché", food.id(), null, true));
        app.categorization.applyToUncategorized();
        assertTrue(app.transactions.get(t.id()).isSplit());

        assertThrows(BusinessException.class, () -> app.categories.delete(home.id()), "utilisee dans une ventilation");
    }

    @Test
    void tagsAreCreatedOnTheFlyAndGiveTotals() {
        Set<Long> holidays = app.tags.resolve(List.of(" Vacances  2026 ", "remboursable", "vacances 2026", ""));
        assertEquals(2, holidays.size(), "doublons ignores, casse comprise");
        assertEquals("Vacances 2026", app.tags.findAll().stream().filter(t -> t.name().startsWith("V")).findFirst()
                .orElseThrow().name(), "espaces superflus retires");

        Transaction split = hypermarket("2026-10-05", TransactionStatus.COMPLETED, holidays);
        Long vacances = app.tags.resolve(List.of("VACANCES 2026")).iterator().next();
        app.transactions.create(new TransactionDraft(checking.id(), LocalDate.of(2026, 10, 7), "Péage",
                new BigDecimal("18.40"), TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null,
                List.of(), Set.of(vacances)));
        app.expense(checking, LocalDate.of(2026, 10, 8), "Sans étiquette", "5", (Long) null);

        TransactionQuery byTag = TransactionQuery.all().withTag(vacances);
        assertEquals(2, app.transactions.search(byTag).size());
        var usage = app.tags.usage(checking.currency());
        assertEquals("Vacances 2026", usage.getFirst().tag().name(), "la plus utilisee d'abord");
        assertEquals(2, usage.getFirst().count());
        assertEquals(Money.eur("-138.40"), usage.getFirst().totals().expenses());

        assertThrows(BusinessException.class, () -> app.tags.rename(vacances, "Remboursable"), "nom deja pris");
        assertThrows(BusinessException.class, () -> app.tags.create("a, b"));
        app.tags.delete(vacances);
        assertEquals(1, app.transactions.get(split.id()).tagIds().size(), "l'operation reste, sans l'etiquette");
        assertTrue(app.transactions.get(split.id()).isSplit());
    }

    @Test
    void validatingAnImportedOperationKeepsItsSplit() {
        Transaction t = hypermarket("2026-10-05", TransactionStatus.COMPLETED, Set.of());
        app.store.markNeedsReview(t.id());
        app.categorization.save(new com.financeapp.core.categorization.CategorizationRule(null, "Hypermarché", food.id(), null, true));

        assertNull(app.inbox.items().getFirst().proposedCategoryId(), "pas de suggestion : deja ventilee");
        app.inbox.validateAllWithCategory();
        app.inbox.validate(t.id(), food.id());
        assertTrue(app.transactions.get(t.id()).isSplit());
        assertEquals(0, app.inbox.count());
    }
}
