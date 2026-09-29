package com.financeapp.infra.db;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.available.AvailableBalanceResult.SectionKind;
import com.financeapp.core.available.HorizonType;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.service.BusinessException;
import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.service.TransferDraft;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JdbcRepositoriesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    @TempDir
    Path dir;
    private SqliteTestDb db;
    private Account checking;
    private Account livret;

    @BeforeEach
    void setUp() {
        db = new SqliteTestDb(dir, TODAY);
        checking = db.accounts.save(Account.create("Compte courant", AccountType.CHECKING, Money.eur("1482.34"), TODAY));
        livret = db.accounts.save(Account.create("Livret A", AccountType.PASSBOOK, Money.eur("4200"), TODAY));
    }

    private Transaction expense(String label, String amount, LocalDate date, TransactionStatus status, Long category) {
        return db.transactions.create(new TransactionDraft(checking.id(), date, label, new BigDecimal(amount),
                TransactionType.EXPENSE, status, category, null));
    }

    @Test
    void defaultCategoriesAreSeeded() {
        Map<Category, List<Category>> tree = db.categories.activeTree();
        Category housing = tree.keySet().stream().filter(c -> "HOUSING".equals(c.systemCode())).findFirst().orElseThrow();
        assertEquals("Logement", housing.name());
        assertEquals(List.of("Loyer", "Électricité", "Gaz", "Eau", "Internet"),
                tree.get(housing).stream().map(Category::name).toList());
        assertEquals(10, tree.size());
    }

    @Test
    void categoryNamesAreUniquePerParentCaseInsensitively() {
        assertThrows(BusinessException.class, () -> db.categories.create(null, "logement", CategoryKind.EXPENSE));
        Category custom = db.categories.create(null, "Animaux", CategoryKind.EXPENSE);
        Category sub = db.categories.create(custom.id(), "Vétérinaire", null);
        assertEquals(CategoryKind.EXPENSE, sub.kind());
        assertEquals("Animaux › Vétérinaire", db.categories.fullName(sub.id()));
    }

    @Test
    void accountsRoundTripIncludingCents() {
        Account reloaded = db.accountRepo.findById(checking.id()).orElseThrow();
        assertEquals(checking, reloaded);
        assertEquals(Money.eur("1482.34"), reloaded.initialBalance());
        assertTrue(reloaded.includeInAvailable());
        assertFalse(db.accountRepo.findById(livret.id()).orElseThrow().includeInAvailable());
    }

    @Test
    void balancesSearchAndStatuses() {
        Category groceries = db.categories.findAll().stream()
                .filter(c -> "FOOD.GROCERIES".equals(c.systemCode())).findFirst().orElseThrow();
        expense("CARREFOUR MARKET", "74.31", TODAY.minusDays(1), TransactionStatus.COMPLETED, groceries.id());
        expense("Carrefour Drive", "25.69", TODAY, TransactionStatus.PENDING, groceries.id());
        expense("Station Total", "68.42", TODAY, TransactionStatus.COMPLETED, null);
        expense("Loyer", "650", TODAY.plusDays(4), TransactionStatus.PLANNED, null);

        assertEquals(Money.eur("1313.92"), db.accounts.balanceOf(checking.id()));

        List<Transaction> carrefour = db.transactions.search(new TransactionQuery(null, null, null, "carrefour",
                null, null, 50, 0));
        assertEquals(2, carrefour.size());
        assertEquals("Carrefour Drive", carrefour.getFirst().label(), "tri par date decroissante");

        Long food = groceries.parentId();
        assertEquals(2, db.transactions.search(new TransactionQuery(null, null, null, null, food, null, 50, 0)).size(),
                "filtrer une categorie inclut ses sous-categories");
        assertEquals(1, db.transactions.search(new TransactionQuery(checking.id(), null, null, null, null,
                Set.of(TransactionStatus.PLANNED), 50, 0)).size());
        assertEquals(1, db.transactionRepo.findPlannedUntil(TODAY.plusDays(10)).size());
        assertEquals(3, db.transactionRepo.findCounted(TODAY.minusDays(1), TODAY).size());
        assertEquals(0, db.transactions.search(new TransactionQuery(null, null, null, "50%", null, null, 50, 0)).size(),
                "les jokers LIKE sont echappes");
    }

    @Test
    void transferIsAtomic() {
        String group = UUID.randomUUID().toString();
        Transaction out = new Transaction(null, checking.id(), TODAY, "Épargne", Money.eur("-500"),
                TransactionType.TRANSFER, TransactionStatus.COMPLETED, null, null, group, livret.id(), null, null);
        Transaction brokenIn = new Transaction(null, 9999, TODAY, "Épargne", Money.eur("500"),
                TransactionType.TRANSFER, TransactionStatus.COMPLETED, null, null, group, checking.id(), null, null);

        assertThrows(RuntimeException.class, () -> db.transactionRepo.insertAll(List.of(out, brokenIn)));
        assertEquals(0, db.transactionRepo.count(), "aucune jambe ne doit subsister");

        db.transactions.createTransfer(new TransferDraft(checking.id(), livret.id(), TODAY, "Épargne",
                new BigDecimal("500"), TransactionStatus.COMPLETED, null));
        Map<Long, Money> balances = db.accounts.balances();
        assertEquals(Money.eur("982.34"), balances.get(checking.id()));
        assertEquals(Money.eur("4700.00"), balances.get(livret.id()));
    }

    @Test
    void accountWithHistoryCannotBeDeleted() {
        expense("Carrefour", "10", TODAY, TransactionStatus.COMPLETED, null);
        assertThrows(BusinessException.class, () -> db.accounts.delete(checking.id()));
        // Meme en contournant le service, la base refuse (ON DELETE RESTRICT).
        assertThrows(RuntimeException.class, () -> db.accountRepo.delete(checking.id()));
        Account empty = db.accounts.save(Account.create("Vide", AccountType.CASH, Money.eur("0"), TODAY));
        db.accounts.delete(empty.id());
        assertTrue(db.accountRepo.findById(empty.id()).isEmpty());
    }

    @Test
    void recurringRulesAndOccurrencesPersist() {
        RecurringRule salary = db.recurring.save(new RecurringRule(null, checking.id(), null, TransactionType.INCOME,
                "Salaire", Money.eur("1850"), null, Frequency.MONTHLY, 1, LocalDate.of(2026, 1, 28), null, null,
                true, true, null));
        RecurringRule saving = db.recurring.save(new RecurringRule(null, checking.id(), livret.id(),
                TransactionType.TRANSFER, "Épargne", Money.eur("100"), null, Frequency.MONTHLY, 1,
                LocalDate.of(2026, 10, 1), null, null, true, true, null));

        RecurringRule reloaded = db.ruleRepo.findById(salary.id()).orElseThrow();
        assertEquals(TODAY, reloaded.trackedFrom());
        assertEquals(Money.eur("1850"), reloaded.amount());

        db.recurring.confirm(saving.id(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), new BigDecimal("100"));
        assertThrows(RuntimeException.class, () -> db.recurring.confirm(saving.id(), LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 1), new BigDecimal("100")));
        assertEquals(1, db.recurring.pendingOccurrences(TODAY, LocalDate.of(2026, 10, 31)).size(),
                "il ne reste que le salaire du 28/10");

        // Supprimer la regle conserve l'historique (recurring_id -> NULL)
        db.recurring.delete(saving.id());
        assertEquals(2, db.transactionRepo.count());
    }

    @Test
    void availableBalanceEndToEndOnSqlite() {
        db.recurring.save(new RecurringRule(null, checking.id(), null, TransactionType.INCOME, "Salaire",
                Money.eur("1850"), null, Frequency.MONTHLY, 1, LocalDate.of(2026, 1, 28), null, null, true, true, null));
        db.recurring.save(new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE, "Loyer",
                Money.eur("650"), null, Frequency.MONTHLY, 1, LocalDate.of(2026, 1, 3), null, null, true, true, null));
        expense("Garagiste", "82.34", TODAY.plusDays(3), TransactionStatus.PLANNED, null);

        var result = db.available.compute(HorizonType.NEXT_PAYDAY, null);

        assertEquals(LocalDate.of(2026, 10, 27), result.horizonEnd());
        assertEquals(Money.eur("-732.34"), result.total(SectionKind.PLANNED_EXPENSES));
        assertEquals(Money.eur("750.00"), result.available());
    }

    @Test
    void settingsPersist() {
        assertEquals(HorizonType.END_OF_MONTH, db.settings.defaultHorizon());
        db.settings.setDefaultHorizon(HorizonType.NEXT_PAYDAY);
        db.settings.setDefaultHorizon(HorizonType.END_OF_WEEK);
        assertEquals(HorizonType.END_OF_WEEK, db.settings.defaultHorizon());
        db.settingsRepo.put("available.horizon", "INCONNU");
        assertEquals(HorizonType.END_OF_MONTH, db.settings.defaultHorizon(), "valeur invalide -> defaut");
    }
}
