package com.financeapp.infra.security;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.available.AvailableBalanceResult;
import com.financeapp.core.available.HorizonType;
import com.financeapp.core.budget.Budget;
import com.financeapp.core.budget.BudgetProgress;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.export.Json;
import com.financeapp.core.goal.GoalProgress;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.service.TransferDraft;
import com.financeapp.core.service.WebExportService;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.infra.db.SqliteTestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jeu de donnees avec budgets et objectifs : le desktop calcule disponible reel,
 * budgets et objectifs, et ecrit ses resultats a cote des donnees exportees. La
 * version web refait les memes calculs sur ces donnees et doit trouver les memes
 * montants, au centime pres (web/src/domain/parity.test.ts).
 */
class WebParityTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @TempDir
    Path dir;

    private static TransactionDraft expense(long account, LocalDate date, String label, String amount,
                                            TransactionStatus status, Long category) {
        return new TransactionDraft(account, date, label, new BigDecimal(amount), TransactionType.EXPENSE, status,
                category, null, List.of(), Set.of());
    }

    private static RecurringRule rule(long account, Long to, TransactionType type, String label, String amount,
                                      Long category, Frequency frequency, LocalDate start, boolean certain) {
        return new RecurringRule(null, account, to, type, label, Money.eur(amount), category, frequency, 1, start,
                null, null, certain, true, null);
    }

    /** Jeu de donnees commun (ids deterministes) : repris par WebImportTest. */
    static void scenario(SqliteTestDb db) {
        LocalDate opening = LocalDate.of(2026, 1, 1);
        Account checking = db.accounts.save(Account.create("Compte courant", AccountType.CHECKING, Money.eur("2350.40"), opening));
        Account joint = db.accounts.save(Account.create("Compte joint", AccountType.JOINT, Money.eur("310"), opening));
        Account livret = db.accounts.save(Account.create("Livret A", AccountType.LIVRET_A, Money.eur("4200"), opening));

        Category food = db.categories.create(null, "Alimentation test", CategoryKind.EXPENSE);
        Category groceries = db.categories.create(food.id(), "Courses test", CategoryKind.EXPENSE);
        Category transport = db.categories.create(null, "Transport test", CategoryKind.EXPENSE);
        Category fuel = db.categories.create(transport.id(), "Carburant test", CategoryKind.EXPENSE);
        Category home = db.categories.create(null, "Logement test", CategoryKind.EXPENSE);
        Category leisure = db.categories.create(null, "Loisirs test", CategoryKind.EXPENSE);
        Category salary = db.categories.create(null, "Salaire test", CategoryKind.INCOME);

        db.transactions.create(expense(checking.id(), LocalDate.of(2026, 9, 28), "Courses septembre", "95.10",
                TransactionStatus.COMPLETED, groceries.id()));
        db.transactions.create(expense(checking.id(), LocalDate.of(2026, 10, 2), "Supermarché", "143.37",
                TransactionStatus.COMPLETED, groceries.id()));
        db.transactions.create(expense(joint.id(), LocalDate.of(2026, 10, 9), "Marché", "38.90",
                TransactionStatus.PENDING, food.id()));
        db.transactions.create(expense(checking.id(), LocalDate.of(2026, 10, 11), "Annulé", "500",
                TransactionStatus.CANCELLED, groceries.id()));
        db.transactions.create(expense(checking.id(), LocalDate.of(2026, 10, 6), "Station", "61.15",
                TransactionStatus.COMPLETED, fuel.id()));
        db.transactions.create(expense(checking.id(), LocalDate.of(2026, 10, 24), "Traiteur (prévu)", "60",
                TransactionStatus.PLANNED, food.id()));
        db.transactions.create(expense(checking.id(), LocalDate.of(2026, 11, 14), "Pneus (prévu)", "89.99",
                TransactionStatus.PLANNED, fuel.id()));
        db.transactions.create(expense(checking.id(), LocalDate.of(2026, 10, 3), "Cinéma", "24",
                TransactionStatus.COMPLETED, leisure.id()));
        db.transactions.createTransfer(new TransferDraft(checking.id(), livret.id(), LocalDate.of(2026, 10, 1),
                "Épargne", new BigDecimal("150"), TransactionStatus.COMPLETED, null));

        db.recurring.save(rule(checking.id(), null, TransactionType.EXPENSE, "Courses hebdo", "42.30", groceries.id(),
                Frequency.WEEKLY, LocalDate.of(2026, 9, 4), true));
        db.recurring.save(rule(checking.id(), null, TransactionType.EXPENSE, "Loyer", "780", home.id(),
                Frequency.MONTHLY, LocalDate.of(2026, 1, 5), true));
        db.recurring.save(rule(checking.id(), null, TransactionType.INCOME, "Salaire", "2480.55", salary.id(),
                Frequency.MONTHLY, LocalDate.of(2026, 1, 28), true));
        db.recurring.save(rule(checking.id(), livret.id(), TransactionType.TRANSFER, "Virement livret", "200", null,
                Frequency.MONTHLY, LocalDate.of(2026, 1, 30), true));

        db.budgets.save(new Budget(null, food.id(), Money.eur("420"), true, true));
        db.budgets.save(new Budget(null, transport.id(), Money.eur("130"), true, true));
        db.budgets.save(new Budget(null, home.id(), Money.eur("800"), false, true));
        db.budgets.save(new Budget(null, leisure.id(), Money.eur("24"), true, true));

        db.goals.save(new SavingsGoal(null, "Vacances", Money.eur("1500"), LocalDate.of(2027, 6, 30), null,
                Money.eur("312.50"), true, false));
        db.goals.save(new SavingsGoal(null, "Fonds d'urgence", Money.eur("10000"), LocalDate.of(2027, 12, 31),
                livret.id(), Money.eur("0"), true, false));
        db.goals.save(new SavingsGoal(null, "Voiture", Money.eur("8000"), null, null, Money.eur("1000"), true, false));
        db.goals.save(new SavingsGoal(null, "Ancien projet", Money.eur("900"), LocalDate.of(2027, 1, 31), null,
                Money.eur("100"), true, true));
        db.goals.save(new SavingsGoal(null, "Atteint", Money.eur("200"), LocalDate.of(2026, 12, 31), null,
                Money.eur("250"), true, false));
    }

    @Test
    void desktopResultsForTheWebParityCheck() throws Exception {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        scenario(db);

        WebExportService.Export export = new WebExportService(db.accounts, db.categories, db.transactionRepo,
                db.recurring, db.tags, db.budgets, db.goals, db.settings).export();
        assertEquals(4, export.report().budgets());
        assertEquals(5, export.report().goals());

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("today", TODAY.toString());
        List<Map<String, Object>> horizons = new ArrayList<>();
        record Case(HorizonType type, LocalDate custom) {
        }
        for (Case c : List.of(new Case(HorizonType.END_OF_WEEK, null), new Case(HorizonType.NEXT_PAYDAY, null),
                new Case(HorizonType.END_OF_MONTH, null), new Case(HorizonType.CUSTOM_DATE, LocalDate.of(2026, 11, 20)),
                new Case(HorizonType.CUSTOM_DATE, LocalDate.of(2027, 2, 15)))) {
            AvailableBalanceResult r = db.available.compute(c.type(), c.custom());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("type", c.type().name());
            o.put("custom", c.custom() == null ? null : c.custom().toString());
            o.put("horizonEnd", r.horizonEnd().toString());
            o.put("available", r.available().toMinorUnits());
            o.put("availableBeforeReservations", r.availableBeforeReservations().toMinorUnits());
            List<Map<String, Object>> lines = new ArrayList<>();
            r.section(AvailableBalanceResult.SectionKind.RESERVATIONS).ifPresent(s -> s.lines().forEach(l -> {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("label", l.label());
                line.put("amount", l.amount().toMinorUnits());
                lines.add(line);
            }));
            o.put("reservations", lines);
            horizons.add(o);
        }
        expected.put("horizons", horizons);

        List<Map<String, Object>> budgets = new ArrayList<>();
        for (YearMonth month : List.of(YearMonth.of(2026, 9), YearMonth.from(TODAY), YearMonth.of(2026, 11))) {
            for (BudgetProgress p : db.budgets.progress(month)) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("month", month.atDay(1).toString());
                o.put("category", p.categoryName());
                o.put("spent", p.spent().toMinorUnits());
                o.put("planned", p.planned().toMinorUnits());
                o.put("remaining", p.remaining().toMinorUnits());
                o.put("permille", p.percent().movePointRight(1).intValueExact());
                o.put("status", p.status().name());
                budgets.add(o);
            }
        }
        expected.put("budgets", budgets);

        List<Map<String, Object>> goals = new ArrayList<>();
        for (GoalProgress p : db.goals.progress()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("name", p.goal().name());
            o.put("saved", p.saved().toMinorUnits());
            o.put("remaining", p.remaining().toMinorUnits());
            o.put("permille", p.percent().movePointRight(1).intValueExact());
            o.put("monthsLeft", p.monthsLeft());
            o.put("monthlyNeeded", p.monthlyNeeded() == null ? null : p.monthlyNeeded().toMinorUnits());
            o.put("reached", p.reached());
            o.put("overdue", p.overdue());
            goals.add(o);
        }
        expected.put("goals", goals);

        // Le scenario couvre bien des reservations de budgets et d'objectifs.
        assertTrue(Json.write(expected).contains("\"label\":\"Budget Alimentation test (reste)\""));
        assertTrue(Json.write(expected).contains("\"label\":\"Objectif Vacances\""));

        Path fixture = Path.of("target", "web-parity-fixture.json");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, "{\"data\":" + export.json() + ",\"expected\":" + Json.write(expected) + "}\n",
                StandardCharsets.UTF_8);
    }
}
