package com.financeapp.infra.db;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.service.BusinessException;
import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Ventilations et etiquettes sur la base SQLite chiffree (migration V8). */
class SplitsAndTagsRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @TempDir
    Path dir;

    @Test
    void splitsAndTagsAreStoredReplacedAndFiltered() {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        Account checking = db.accounts.save(Account.create("Courant", AccountType.CHECKING, Money.eur("1000"), TODAY.minusYears(1)));
        Category food = db.categories.create(null, "Test alim", CategoryKind.EXPENSE);
        Category groceries = db.categories.create(food.id(), "Test courses", CategoryKind.EXPENSE);
        Category home = db.categories.create(null, "Test maison", CategoryKind.EXPENSE);
        Set<Long> tags = db.tags.resolve(List.of("Vacances", "Remboursable"));

        Transaction t = db.transactions.create(new TransactionDraft(checking.id(), TODAY, "Hypermarché",
                new BigDecimal("120"), TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null,
                List.of(new TransactionDraft.Split(groceries.id(), new BigDecimal("90")),
                        new TransactionDraft.Split(home.id(), new BigDecimal("30"))), tags));
        db.transactions.create(new TransactionDraft(checking.id(), TODAY, "Peinture", new BigDecimal("45"),
                TransactionType.EXPENSE, TransactionStatus.COMPLETED, home.id(), null));

        Transaction read = db.transactions.get(t.id());
        assertEquals(List.of(groceries.id(), home.id()), read.splits().stream().map(l -> l.categoryId()).toList(), "ordre conserve");
        assertEquals(List.of(Money.eur("-90"), Money.eur("-30")), read.splits().stream().map(l -> l.amount()).toList());
        assertEquals(tags, read.tagIds());
        assertNull(read.categoryId());
        assertEquals(Money.eur("835"), db.accounts.balanceOf(checking.id()));

        TransactionQuery byFood = new TransactionQuery(null, null, null, null, food.id(), null, 50, 0);
        assertEquals(List.of(t.id()), db.transactions.search(byFood).stream().map(Transaction::id).toList(),
                "trouvee par la categorie parente d'une ligne");
        assertEquals(Money.eur("-90"), db.transactions.summarize(byFood, checking.currency()).expenses());
        TransactionQuery byHome = new TransactionQuery(null, null, null, null, home.id(), null, 50, 0);
        assertEquals(Money.eur("-75"), db.transactions.summarize(byHome, checking.currency()).expenses(), "30 + 45");
        Long vacances = db.tags.resolve(List.of("vacances")).iterator().next();
        assertEquals(1, db.transactions.search(TransactionQuery.all().withTag(vacances)).size());

        // Remplacement : une seule categorie, une seule etiquette.
        db.transactions.update(t.id(), new TransactionDraft(checking.id(), TODAY, "Hypermarché", new BigDecimal("120"),
                TransactionType.EXPENSE, TransactionStatus.COMPLETED, food.id(), null, List.of(), Set.of(vacances)));
        read = db.transactions.get(t.id());
        assertFalse(read.isSplit());
        assertEquals(food.id(), read.categoryId());
        assertEquals(Set.of(vacances), read.tagIds());
        assertEquals(0L, db.jdbc.sql("SELECT count(*) FROM transaction_splits").query(Long.class).single());

        // Statut modifie : etiquettes conservees.
        db.transactions.setStatus(t.id(), TransactionStatus.PENDING);
        assertEquals(Set.of(vacances), db.transactions.get(t.id()).tagIds());
    }

    @Test
    void deletionsCascadeAndUsedCategoriesAreProtected() {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        Account checking = db.accounts.save(Account.create("Courant", AccountType.CHECKING, Money.eur("1000"), TODAY.minusYears(1)));
        Category a = db.categories.create(null, "Test A", CategoryKind.EXPENSE);
        Category b = db.categories.create(null, "Test B", CategoryKind.EXPENSE);
        Set<Long> tags = db.tags.resolve(List.of("Travaux"));
        Transaction t = db.transactions.create(new TransactionDraft(checking.id(), TODAY, "Magasin", new BigDecimal("10"),
                TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null,
                List.of(new TransactionDraft.Split(a.id(), new BigDecimal("4")),
                        new TransactionDraft.Split(b.id(), new BigDecimal("6"))), tags));

        assertThrows(BusinessException.class, () -> db.categories.delete(b.id()), "utilisee dans une ventilation");
        assertEquals(1, db.tags.usage(checking.currency()).getFirst().count());

        db.tags.delete(tags.iterator().next());
        assertTrue(db.transactions.get(t.id()).tagIds().isEmpty(), "operation conservee, etiquette retiree");
        assertTrue(db.transactions.get(t.id()).isSplit());

        db.transactions.delete(t.id());
        assertEquals(0L, db.jdbc.sql("SELECT count(*) FROM transaction_splits").query(Long.class).single(), "cascade");
        db.categories.delete(b.id());
    }

    @Test
    void manyOperationsAreReadWithTheirDetails() {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        Account checking = db.accounts.save(Account.create("Courant", AccountType.CHECKING, Money.eur("100000"), TODAY.minusYears(5)));
        Set<Long> tag = db.tags.resolve(List.of("Lot"));
        for (int i = 0; i < 1200; i++) { // plus de deux paquets de lecture
            db.transactions.create(new TransactionDraft(checking.id(), TODAY.minusDays(i % 300), "Op " + i,
                    BigDecimal.ONE, TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null, List.of(), tag));
        }
        List<Transaction> all = db.transactionRepo.findCounted(TODAY.minusYears(1), TODAY);
        assertEquals(1200, all.size());
        assertTrue(all.stream().allMatch(t -> t.tagIds().equals(tag)));
    }

    @Test
    void recurringSplitsAreStoredAndProtectTheirCategories() {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        Account checking = db.accounts.save(Account.create("Courant", AccountType.CHECKING, Money.eur("3000"), TODAY.minusYears(1)));
        Category rent = db.categories.create(null, "Test loyer", CategoryKind.EXPENSE);
        Category charges = db.categories.create(null, "Test charges", CategoryKind.EXPENSE);
        var rule = db.recurring.save(new com.financeapp.core.recurring.RecurringRule(null, checking.id(), null,
                TransactionType.EXPENSE, "Loyer", Money.eur("800"), null, com.financeapp.core.recurring.Frequency.MONTHLY,
                1, TODAY.withDayOfMonth(20), null, null, true, true, null,
                List.of(new com.financeapp.core.transaction.SplitLine(rent.id(), Money.eur("700")),
                        new com.financeapp.core.transaction.SplitLine(charges.id(), Money.eur("100")))));

        var read = db.ruleRepo.findById(rule.id()).orElseThrow();
        assertEquals(List.of(rent.id(), charges.id()), read.splits().stream().map(l -> l.categoryId()).toList());
        assertEquals(Money.eur("700"), read.splits().getFirst().amount());
        assertNull(read.categoryId());
        assertEquals(1, db.ruleRepo.findAll().size());
        assertThrows(BusinessException.class, () -> db.categories.delete(charges.id()), "utilisee dans une ventilation");

        Transaction t = db.recurring.confirm(rule.id(), TODAY.withDayOfMonth(20), TODAY.withDayOfMonth(20),
                new BigDecimal("800")).getFirst();
        assertEquals(2, db.transactions.get(t.id()).splits().size(), "l'occurrence validee est ventilee");

        db.recurring.save(read.withEndDate(TODAY.plusMonths(6)));
        assertEquals(2, db.ruleRepo.findById(rule.id()).orElseThrow().splits().size(), "conservee a la modification");
        db.recurring.delete(rule.id());
        assertEquals(0L, db.jdbc.sql("SELECT count(*) FROM recurring_splits").query(Long.class).single(), "cascade");
    }
}
