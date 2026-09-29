package com.financeapp.infra.db;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.available.AvailableBalanceResult.SectionKind;
import com.financeapp.core.available.HorizonType;
import com.financeapp.core.budget.Budget;
import com.financeapp.core.category.Category;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.SearchTotals;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class V2RepositoriesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @TempDir
    Path dir;
    private SqliteTestDb db;
    private Account checking;
    private Account livret;
    private Category groceries;

    @BeforeEach
    void setUp() {
        db = new SqliteTestDb(dir, TODAY);
        checking = db.accounts.save(Account.create("Compte courant", AccountType.CHECKING, Money.eur("1000"), TODAY));
        livret = db.accounts.save(Account.create("Livret", AccountType.PASSBOOK, Money.eur("800"), TODAY));
        groceries = db.categories.findAll().stream().filter(c -> "FOOD.GROCERIES".equals(c.systemCode())).findFirst().orElseThrow();
    }

    private void expense(String label, String amount, LocalDate date, Long category, TransactionStatus status) {
        db.transactions.create(new TransactionDraft(checking.id(), date, label, new BigDecimal(amount),
                TransactionType.EXPENSE, status, category, null));
    }

    @Test
    void budgetsPersistAndOnlyOneActivePerCategory() {
        Budget b = db.budgets.save(new Budget(null, groceries.parentId(), Money.eur("300"), true, true));
        assertEquals(b, db.budgetRepo.findById(b.id()).orElseThrow());
        // Contrainte en base, meme en contournant le service
        assertThrows(RuntimeException.class, () -> db.budgetRepo.save(new Budget(null, groceries.parentId(), Money.eur("1"), true, true)));
        db.budgetRepo.save(new Budget(b.id(), b.categoryId(), b.limit(), b.reserveInAvailable(), false));
        db.budgetRepo.save(new Budget(null, groceries.parentId(), Money.eur("250"), true, true));
        assertEquals(2, db.budgetRepo.findAll().size());
    }

    @Test
    void budgetProgressAndReservationOnSqlite() {
        db.budgets.save(new Budget(null, groceries.parentId(), Money.eur("300"), true, true));
        expense("Carrefour", "184", TODAY.minusDays(2), groceries.id(), TransactionStatus.COMPLETED);
        expense("Carrefour annulé", "50", TODAY.minusDays(2), groceries.id(), TransactionStatus.CANCELLED);

        var progress = db.budgets.progress(YearMonth.from(TODAY)).getFirst();
        assertEquals(Money.eur("184"), progress.spent());
        assertEquals(Money.eur("116"), progress.remaining());

        var r = db.available.compute(HorizonType.END_OF_MONTH, null);
        assertEquals(Money.eur("-116"), r.total(SectionKind.RESERVATIONS));
        assertEquals(Money.eur("700"), r.available());
    }

    @Test
    void savingsGoalsPersist() {
        SavingsGoal g = db.goals.save(new SavingsGoal(null, "Fonds d'urgence", Money.eur("5000"),
                LocalDate.of(2027, 12, 31), livret.id(), Money.eur("0"), false, false));
        assertEquals(g, db.goalRepo.findById(g.id()).orElseThrow());
        SavingsGoal manual = db.goals.save(new SavingsGoal(null, "Vacances", Money.eur("1200"), null, null,
                Money.eur("150.50"), true, false));
        db.goals.addContribution(manual.id(), new BigDecimal("49.50"));
        assertEquals(Money.eur("200"), db.goalRepo.findById(manual.id()).orElseThrow().manualSaved());
        assertEquals(Money.eur("800"), db.goals.progress().getFirst().saved(), "solde du livret lie");
    }

    @Test
    void advancedSearchAndTotalsInSql() {
        expense("CARREFOUR MARKET", "46.30", TODAY.minusDays(3), groceries.id(), TransactionStatus.COMPLETED);
        expense("Carrefour Drive", "90.00", TODAY.minusDays(10), groceries.id(), TransactionStatus.COMPLETED);
        expense("Carrefour", "12.00", TODAY.minusDays(2), null, TransactionStatus.COMPLETED);
        expense("Carrefour annulé", "80.00", TODAY.minusDays(2), groceries.id(), TransactionStatus.CANCELLED);
        expense("Total", "60.00", TODAY.minusDays(1), null, TransactionStatus.COMPLETED);
        db.transactions.create(new TransactionDraft(checking.id(), TODAY, "Remboursement Carrefour", new BigDecimal("20"),
                TransactionType.INCOME, TransactionStatus.COMPLETED, null, null));

        TransactionQuery byText = new TransactionQuery(null, null, null, "carrefour", null, null, 100, 0);
        SearchTotals all = db.transactions.summarize(byText, Money.EUR);
        assertEquals(4, all.count(), "l'operation annulee est exclue des totaux");
        assertEquals(Money.eur("-148.30"), all.expenses());
        assertEquals(Money.eur("20"), all.income());
        assertEquals(Money.eur("49.43"), all.averageExpense());

        TransactionQuery filtered = new TransactionQuery(null, TODAY.minusDays(5), TODAY, "carrefour",
                groceries.parentId(), null, TransactionType.EXPENSE, new BigDecimal("40"), new BigDecimal("100"), 100, 0);
        assertEquals(2, db.transactions.search(filtered).size(), "annulee comprise dans la liste, pas dans les totaux");
        assertEquals(1, db.transactions.summarize(filtered, Money.EUR).count());
        assertEquals(1, db.transactions.search(new TransactionQuery(null, null, null, null, null, null,
                TransactionType.EXPENSE, new BigDecimal("46.30"), new BigDecimal("46.30"), 10, 0)).size(), "bornes incluses");
    }
}
