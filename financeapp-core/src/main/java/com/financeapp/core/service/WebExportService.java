package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.budget.Budget;
import com.financeapp.core.category.Category;
import com.financeapp.core.export.Json;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.core.transaction.SplitLine;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Donnees du profil au format de la version web (FinanceData, version 1) : comptes,
 * categories, operations, recurrences, etiquettes, budgets, objectifs d'epargne et reglages. Le resultat est ensuite
 * chiffre au format des sauvegardes web (infra).
 *
 * <p>Adaptations, toutes signalees dans le compte rendu :
 * <ul>
 *   <li>seuls les comptes dans la devise de reference sont exportes (la version web ne
 *       gere qu'une devise) ; un virement avec un compte non exporte devient une
 *       depense ou un revenu ;</li>
 *   <li>une ventilation garde la categorie de sa plus grosse ligne ; le detail est ajoute
 *       au commentaire ;</li>
 *   <li>le solde actuel de chaque compte est conserve exactement (valeurs d'epargne
 *       saisies comprises) : le solde initial exporte est ajuste en consequence.</li>
 * </ul>
 */
public final class WebExportService {

    /** Ce qui a ete exporte et ce qui ne l'a pas ete. */
    public record Report(int accounts, int categories, int transactions, int rules, int budgets, int goals,
                         int skippedAccounts, int skippedTransactions, int skippedRules, int splitsSimplified) {
    }

    /** Donnees au format web (JSON) et compte rendu. */
    public record Export(String json, Report report) {
    }

    private final AccountService accounts;
    private final CategoryService categories;
    private final TransactionRepository transactions;
    private final RecurringService recurring;
    private final TagService tags;
    private final BudgetService budgets;
    private final SavingsGoalService goals;
    private final SettingsService settings;

    public WebExportService(AccountService accounts, CategoryService categories, TransactionRepository transactions,
                            RecurringService recurring, TagService tags, BudgetService budgets,
                            SavingsGoalService goals, SettingsService settings) {
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.recurring = recurring;
        this.tags = tags;
        this.budgets = budgets;
        this.goals = goals;
        this.settings = settings;
    }

    public Export export() {
        Currency currency = settings.baseCurrency();
        Map<Long, Money> balances = accounts.balances();
        List<Account> allAccounts = accounts.findAll();
        Set<Long> kept = allAccounts.stream().filter(a -> a.currency().equals(currency)).map(Account::id)
                .collect(Collectors.toSet());
        Map<Long, String> accountNames = allAccounts.stream().collect(Collectors.toMap(Account::id, Account::name));
        Map<Long, String> categoryNames = categories.findAll().stream()
                .collect(Collectors.toMap(Category::id, Category::name));
        Map<Long, String> tagNames = tags.names();
        long maxId = 0;

        // --- Operations (toutes, par pages) -----------------------------------
        List<Transaction> all = new ArrayList<>();
        for (int offset = 0; ; offset += 500) {
            List<Transaction> page = transactions.search(new TransactionQuery(null, null, null, null, null, null,
                    500, offset));
            all.addAll(page);
            if (page.size() < 500) {
                break;
            }
        }
        all.sort(Comparator.comparing(Transaction::date).thenComparing(Transaction::id));

        List<Map<String, Object>> txOut = new ArrayList<>();
        Map<Long, Long> countedByAccount = new HashMap<>();
        int skippedTx = 0;
        int simplified = 0;
        for (Transaction t : all) {
            if (!kept.contains(t.accountId())) {
                skippedTx++;
                continue;
            }
            maxId = Math.max(maxId, t.id());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", t.id());
            o.put("accountId", t.accountId());
            o.put("date", t.date().toString());
            o.put("label", t.label());
            o.put("amount", t.amount().toMinorUnits());
            String type = t.type().name();
            Long categoryId = t.categoryId();
            String note = t.note();
            boolean transfer = t.type() == TransactionType.TRANSFER;
            if (transfer && (t.transferAccountId() == null || !kept.contains(t.transferAccountId()))) {
                // L'autre compte n'est pas exporte : la jambe devient un mouvement simple.
                type = t.amount().isNegative() ? "EXPENSE" : "INCOME";
                note = append(note, "Virement " + (t.amount().isNegative() ? "vers " : "depuis ")
                        + accountNames.getOrDefault(t.transferAccountId(), "un autre compte") + " (non exporté)");
                transfer = false;
            }
            if (t.isSplit()) {
                simplified++;
                categoryId = mainCategory(t.splits());
                note = append(note, "Ventilée : " + describe(t.splits(), categoryNames));
            }
            o.put("type", type);
            o.put("status", t.status().name());
            o.put("categoryId", transfer || categoryId == null ? Json.NULL : categoryId);
            o.put("note", note == null || note.isBlank() ? null : note);
            o.put("tags", t.tagIds().stream().map(tagNames::get).filter(n -> n != null).sorted().toList());
            if (transfer) {
                o.put("transferGroup", t.transferGroup());
                o.put("transferAccountId", t.transferAccountId());
            }
            if (t.recurringId() != null) {
                o.put("recurringId", t.recurringId());
                o.put("occurrenceDate", t.occurrenceDate().toString());
            }
            txOut.add(o);
            if (t.status().countsInBalance()) {
                countedByAccount.merge(t.accountId(), t.amount().toMinorUnits(), Long::sum);
            }
        }

        // --- Comptes : solde actuel conserve ----------------------------------
        List<Map<String, Object>> accountsOut = new ArrayList<>();
        int skippedAccounts = 0;
        for (Account a : allAccounts) {
            if (!kept.contains(a.id())) {
                skippedAccounts++;
                continue;
            }
            maxId = Math.max(maxId, a.id());
            long balance = balances.get(a.id()).toMinorUnits();
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", a.id());
            o.put("name", a.name());
            o.put("type", a.type().name());
            o.put("initialBalance", balance - countedByAccount.getOrDefault(a.id(), 0L));
            o.put("openingDate", a.openingDate().toString());
            o.put("includeInAvailable", a.includeInAvailable());
            o.put("archived", a.archived());
            o.put("color", a.color());
            accountsOut.add(o);
        }

        // --- Categories ---------------------------------------------------------
        List<Map<String, Object>> categoriesOut = new ArrayList<>();
        for (Category c : categories.findAll()) {
            maxId = Math.max(maxId, c.id());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", c.id());
            o.put("parentId", c.parentId() == null ? Json.NULL : c.parentId());
            o.put("name", c.name());
            o.put("kind", c.kind().name());
            o.put("archived", c.archived());
            o.put("code", c.systemCode());
            categoriesOut.add(o);
        }

        // --- Recurrences ----------------------------------------------------------
        List<Map<String, Object>> rulesOut = new ArrayList<>();
        int skippedRules = 0;
        for (RecurringRule r : recurring.findAll()) {
            if (!kept.contains(r.accountId()) || (r.toAccountId() != null && !kept.contains(r.toAccountId()))) {
                skippedRules++;
                continue;
            }
            maxId = Math.max(maxId, r.id());
            String note = r.note();
            Long categoryId = r.categoryId();
            if (r.isSplit()) {
                simplified++;
                categoryId = mainCategory(r.splits());
                note = append(note, "Ventilée : " + describe(r.splits(), categoryNames));
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", r.id());
            o.put("accountId", r.accountId());
            o.put("toAccountId", r.toAccountId());
            o.put("type", r.type().name());
            o.put("label", r.label());
            o.put("amount", r.amount().toMinorUnits());
            o.put("categoryId", r.type() == TransactionType.TRANSFER || categoryId == null ? Json.NULL : categoryId);
            o.put("frequency", r.frequency().name());
            o.put("interval", r.interval());
            o.put("startDate", r.startDate().toString());
            o.put("endDate", r.endDate() == null ? null : r.endDate().toString());
            o.put("trackedFrom", r.trackedFrom().toString());
            o.put("certain", r.certain());
            o.put("active", r.active());
            o.put("note", note == null || note.isBlank() ? null : note);
            o.put("tags", r.tagIds().stream().map(tagNames::get).filter(n -> n != null).sorted().toList());
            rulesOut.add(o);
        }

        // --- Budgets et objectifs (devise de reference) ----------------------------
        List<Map<String, Object>> budgetsOut = new ArrayList<>();
        for (Budget b : budgets.findAll()) {
            if (!b.limit().currency().equals(currency)) {
                continue;
            }
            maxId = Math.max(maxId, b.id());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", b.id());
            o.put("categoryId", b.categoryId());
            o.put("limit", b.limit().toMinorUnits());
            o.put("reserveInAvailable", b.reserveInAvailable());
            o.put("active", b.active());
            budgetsOut.add(o);
        }
        List<Map<String, Object>> goalsOut = new ArrayList<>();
        for (SavingsGoal g : goals.findAll()) {
            if (!g.target().currency().equals(currency)
                    || (g.linkedAccountId() != null && !kept.contains(g.linkedAccountId()))) {
                continue;
            }
            maxId = Math.max(maxId, g.id());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", g.id());
            o.put("name", g.name());
            o.put("target", g.target().toMinorUnits());
            o.put("targetDate", g.targetDate() == null ? null : g.targetDate().toString());
            o.put("linkedAccountId", g.linkedAccountId());
            o.put("manualSaved", g.manualSaved().toMinorUnits());
            o.put("reserveInAvailable", g.reserveInAvailable());
            o.put("archived", g.archived());
            goalsOut.add(o);
        }

        Map<String, Object> settingsOut = new LinkedHashMap<>();
        settingsOut.put("defaultHorizon", settings.defaultHorizon().name());
        settingsOut.put("includeCertainIncome", settings.includeCertainIncome());
        settingsOut.put("autoLockMinutes", settings.autoLockMinutes());
        settingsOut.put("privacy", settings.privacyMode());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        data.put("nextId", maxId + 1);
        data.put("settings", settingsOut);
        data.put("accounts", accountsOut);
        data.put("categories", categoriesOut);
        data.put("transactions", txOut);
        data.put("rules", rulesOut);
        data.put("budgets", budgetsOut);
        data.put("goals", goalsOut);
        return new Export(Json.write(data), new Report(accountsOut.size(), categoriesOut.size(),
                txOut.size(), rulesOut.size(), budgetsOut.size(), goalsOut.size(), skippedAccounts, skippedTx,
                skippedRules, simplified));
    }

    private static Long mainCategory(List<SplitLine> splits) {
        return splits.stream().max(Comparator.comparing(l -> l.amount().amount().abs())).orElseThrow().categoryId();
    }

    private static String describe(List<SplitLine> splits, Map<Long, String> names) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.FRANCE);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return splits.stream().map(l -> names.getOrDefault(l.categoryId(), "Sans catégorie") + " ("
                + f.format(l.amount().amount().abs()) + ")").collect(Collectors.joining(" + "));
    }

    private static String append(String note, String text) {
        return note == null || note.isBlank() ? text : note + " · " + text;
    }
}
