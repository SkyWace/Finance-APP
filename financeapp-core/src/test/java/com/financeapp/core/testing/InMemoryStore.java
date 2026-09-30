package com.financeapp.core.testing;

import com.financeapp.core.account.Account;
import com.financeapp.core.category.Category;
import com.financeapp.core.budget.Budget;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.money.Money;
import com.financeapp.core.categorization.CategorizationRule;
import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportedTransaction;
import com.financeapp.core.imports.Reconciliation;
import com.financeapp.core.port.AccountRepository;
import com.financeapp.core.port.CategorizationRuleRepository;
import com.financeapp.core.port.ImportRepository;
import com.financeapp.core.port.BudgetRepository;
import com.financeapp.core.port.SavingsGoalRepository;
import com.financeapp.core.port.SearchTotals;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.core.port.CategoryRepository;
import com.financeapp.core.port.OccurrenceKey;
import com.financeapp.core.port.RecurringRuleRepository;
import com.financeapp.core.port.SettingsRepository;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.transaction.Transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Implementation en memoire de tous les ports, pour tester les services sans base. */
public final class InMemoryStore {

    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, Account> accountMap = new LinkedHashMap<>();
    private final Map<Long, Category> categoryMap = new LinkedHashMap<>();
    private final Map<Long, Transaction> transactionMap = new LinkedHashMap<>();
    private final Map<Long, RecurringRule> ruleMap = new LinkedHashMap<>();
    private final Map<String, String> settingsMap = new HashMap<>();
    private final Map<Long, Budget> budgetMap = new LinkedHashMap<>();
    private final Map<Long, CategorizationRule> ruleMapCat = new LinkedHashMap<>();
    private final Set<Long> needsReview = new java.util.HashSet<>();
    private final Map<Long, String> externalIdByTx = new HashMap<>();
    private final Map<Long, Long> batchByTx = new HashMap<>();
    private final Map<Long, ImportBatch> batchMap = new LinkedHashMap<>();
    private final Map<Long, List<Reconciliation>> reconciliationsByBatch = new HashMap<>();
    private final Map<Long, SavingsGoal> goalMap = new LinkedHashMap<>();

    public final AccountRepository accounts = new AccountRepository() {
        public List<Account> findAll() { return List.copyOf(accountMap.values()); }
        public Optional<Account> findById(long id) { return Optional.ofNullable(accountMap.get(id)); }
        public Account save(Account a) {
            Account saved = a.id() == null ? a.withId(ids.getAndIncrement()) : a;
            accountMap.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) { accountMap.remove(id); }
    };

    public final CategoryRepository categories = new CategoryRepository() {
        public List<Category> findAll() { return List.copyOf(categoryMap.values()); }
        public Optional<Category> findById(long id) { return Optional.ofNullable(categoryMap.get(id)); }
        public Category save(Category c) {
            Category saved = c.id() == null ? c.withId(ids.getAndIncrement()) : c;
            categoryMap.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) { categoryMap.remove(id); }
        public long countUsages(long id) {
            return transactionMap.values().stream().filter(t -> Objects.equals(t.categoryId(), id)).count()
                    + ruleMap.values().stream().filter(r -> Objects.equals(r.categoryId(), id)).count()
                    + categoryMap.values().stream().filter(c -> Objects.equals(c.parentId(), id)).count();
        }
    };

    public final TransactionRepository transactions = new TransactionRepository() {
        public Transaction insert(Transaction t) {
            Transaction saved = t.withId(ids.getAndIncrement());
            transactionMap.put(saved.id(), saved);
            return saved;
        }
        public List<Transaction> insertAll(List<Transaction> list) { return list.stream().map(this::insert).toList(); }
        public Transaction update(Transaction t) { transactionMap.put(t.id(), t); return t; }
        public void updateAll(List<Transaction> list) { list.forEach(this::update); }
        public void delete(long id) { transactionMap.remove(id); }
        public void deleteTransferGroup(String group) {
            transactionMap.values().removeIf(t -> group.equals(t.transferGroup()));
        }
        public Optional<Transaction> findById(long id) { return Optional.ofNullable(transactionMap.get(id)); }
        public List<Transaction> findByTransferGroup(String group) {
            return transactionMap.values().stream().filter(t -> group.equals(t.transferGroup())).toList();
        }
        private java.util.stream.Stream<Transaction> matching(TransactionQuery q) {
            Set<Long> categories = q.categoryId() == null ? null : new java.util.HashSet<>(java.util.List.of(q.categoryId()));
            if (categories != null) {
                categoryMap.values().stream().filter(c -> q.categoryId().equals(c.parentId())).forEach(c -> categories.add(c.id()));
            }
            return transactionMap.values().stream()
                    .filter(t -> q.accountId() == null || t.accountId() == q.accountId())
                    .filter(t -> q.from() == null || !t.date().isBefore(q.from()))
                    .filter(t -> q.to() == null || !t.date().isAfter(q.to()))
                    .filter(t -> q.text() == null || t.label().toLowerCase().contains(q.text().toLowerCase()))
                    .filter(t -> categories == null || categories.contains(t.categoryId()))
                    .filter(t -> q.statuses() == null || q.statuses().contains(t.status()))
                    .filter(t -> q.type() == null || t.type() == q.type())
                    .filter(t -> q.minAmount() == null || t.amount().abs().amount().compareTo(q.minAmount()) >= 0)
                    .filter(t -> q.maxAmount() == null || t.amount().abs().amount().compareTo(q.maxAmount()) <= 0);
        }
        public List<Transaction> search(TransactionQuery q) {
            return matching(q)
                    .sorted(Comparator.comparing(Transaction::date).thenComparing(Transaction::id).reversed())
                    .skip(q.offset()).limit(q.limit())
                    .toList();
        }
        public SearchTotals summarize(TransactionQuery q, java.util.Currency currency) {
            List<Transaction> rows = matching(q)
                    .filter(t -> t.status() != TransactionStatus.CANCELLED && t.type() != TransactionType.TRANSFER)
                    .filter(t -> t.amount().currency().equals(currency)).toList();
            Money zero = Money.zero(currency);
            List<Transaction> expenses = rows.stream().filter(t -> t.type() == TransactionType.EXPENSE).toList();
            return new SearchTotals(rows.size(), expenses.size(),
                    expenses.stream().map(Transaction::amount).reduce(zero, Money::plus),
                    rows.stream().filter(t -> t.type() == TransactionType.INCOME).map(Transaction::amount).reduce(zero, Money::plus));
        }
        public Map<Long, Long> sumCountedMinorByAccount() {
            return transactionMap.values().stream().filter(t -> t.status().countsInBalance())
                    .collect(Collectors.groupingBy(Transaction::accountId,
                            Collectors.summingLong(t -> t.amount().toMinorUnits())));
        }
        public List<Transaction> findCounted(LocalDate from, LocalDate to) {
            return transactionMap.values().stream().filter(t -> t.status().countsInBalance())
                    .filter(t -> !t.date().isBefore(from) && !t.date().isAfter(to)).toList();
        }
        public List<Transaction> findPlannedUntil(LocalDate until) {
            return transactionMap.values().stream()
                    .filter(t -> t.status() == com.financeapp.core.transaction.TransactionStatus.PLANNED)
                    .filter(t -> !t.date().isAfter(until)).toList();
        }
        public Set<OccurrenceKey> findMaterializedOccurrences(LocalDate from) {
            return transactionMap.values().stream()
                    .filter(t -> t.recurringId() != null && !t.occurrenceDate().isBefore(from))
                    .map(t -> new OccurrenceKey(t.recurringId(), t.occurrenceDate()))
                    .collect(Collectors.toSet());
        }
        public boolean existsForAccount(long accountId) {
            return transactionMap.values().stream().anyMatch(t -> t.accountId() == accountId);
        }
        public List<Transaction> findNeedingReview() {
            return transactionMap.values().stream().filter(t -> needsReview.contains(t.id()))
                    .sorted(Comparator.comparing(Transaction::date)).toList();
        }
        public long countNeedingReview() { return needsReview.stream().filter(transactionMap::containsKey).count(); }
        public void markReviewed(long id) { needsReview.remove(id); }
    };

    public final RecurringRuleRepository rules = new RecurringRuleRepository() {
        public List<RecurringRule> findAll() { return List.copyOf(ruleMap.values()); }
        public Optional<RecurringRule> findById(long id) { return Optional.ofNullable(ruleMap.get(id)); }
        public RecurringRule save(RecurringRule r) {
            RecurringRule saved = r.id() == null ? r.withId(ids.getAndIncrement()) : r;
            ruleMap.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) { ruleMap.remove(id); }
    };

    public final SettingsRepository settings = new SettingsRepository() {
        public Optional<String> get(String key) { return Optional.ofNullable(settingsMap.get(key)); }
        public void put(String key, String value) { settingsMap.put(key, value); }
    };

    public final BudgetRepository budgets = new BudgetRepository() {
        public List<Budget> findAll() { return List.copyOf(budgetMap.values()); }
        public Optional<Budget> findById(long id) { return Optional.ofNullable(budgetMap.get(id)); }
        public Budget save(Budget b) {
            Budget saved = b.id() == null ? b.withId(ids.getAndIncrement()) : b;
            budgetMap.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) { budgetMap.remove(id); }
    };

    public final SavingsGoalRepository goals = new SavingsGoalRepository() {
        public List<SavingsGoal> findAll() { return List.copyOf(goalMap.values()); }
        public Optional<SavingsGoal> findById(long id) { return Optional.ofNullable(goalMap.get(id)); }
        public SavingsGoal save(SavingsGoal g) {
            SavingsGoal saved = g.id() == null ? g.withId(ids.getAndIncrement()) : g;
            goalMap.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) { goalMap.remove(id); }
    };

    public final CategorizationRuleRepository categorizationRules = new CategorizationRuleRepository() {
        public List<CategorizationRule> findAll() { return List.copyOf(ruleMapCat.values()); }
        public Optional<CategorizationRule> findById(long id) { return Optional.ofNullable(ruleMapCat.get(id)); }
        public CategorizationRule save(CategorizationRule r) {
            CategorizationRule saved = r.id() == null ? r.withId(ids.getAndIncrement()) : r;
            ruleMapCat.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) { ruleMapCat.remove(id); }
    };

    public final ImportRepository imports = new ImportRepository() {
        public ImportBatch commit(ImportBatch batch, List<ImportedTransaction> created, List<Reconciliation> reconciliations) {
            ImportBatch saved = batch.withId(ids.getAndIncrement());
            batchMap.put(saved.id(), saved);
            for (ImportedTransaction it : created) {
                Transaction t = transactions.insert(it.transaction());
                needsReview.add(t.id());
                batchByTx.put(t.id(), saved.id());
                if (it.externalId() != null) {
                    externalIdByTx.put(t.id(), it.externalId());
                }
            }
            for (Reconciliation r : reconciliations) {
                Transaction t = transactionMap.get(r.transactionId());
                transactionMap.put(t.id(), new Transaction(t.id(), t.accountId(), r.newDate(), r.newLabel(), t.amount(),
                        t.type(), TransactionStatus.COMPLETED, t.categoryId(), t.note(), t.transferGroup(),
                        t.transferAccountId(), t.recurringId(), t.occurrenceDate()));
            }
            reconciliationsByBatch.put(saved.id(), List.copyOf(reconciliations));
            return saved;
        }
        public List<ImportBatch> findAll() { return List.copyOf(batchMap.values()).reversed(); }
        public void undo(long batchId) {
            batchByTx.entrySet().removeIf(e -> {
                if (e.getValue() == batchId) {
                    transactionMap.remove(e.getKey());
                    needsReview.remove(e.getKey());
                    externalIdByTx.remove(e.getKey());
                    return true;
                }
                return false;
            });
            for (Reconciliation r : reconciliationsByBatch.getOrDefault(batchId, List.of())) {
                Transaction t = transactionMap.get(r.transactionId());
                transactionMap.put(t.id(), new Transaction(t.id(), t.accountId(), r.previousDate(), r.previousLabel(),
                        t.amount(), t.type(), r.previousStatus(), t.categoryId(), t.note(), t.transferGroup(),
                        t.transferAccountId(), t.recurringId(), t.occurrenceDate()));
            }
            ImportBatch b = batchMap.get(batchId);
            batchMap.put(batchId, new ImportBatch(b.id(), b.accountId(), b.fileName(), b.format(), b.importedAt(),
                    b.created(), b.reconciled(), b.skipped(), true));
        }
        public Set<String> externalIds(long accountId) {
            return externalIdByTx.entrySet().stream()
                    .filter(e -> transactionMap.containsKey(e.getKey()) && transactionMap.get(e.getKey()).accountId() == accountId)
                    .map(Map.Entry::getValue).collect(Collectors.toSet());
        }
    };

    public List<Transaction> allTransactions() {
        return new ArrayList<>(transactionMap.values());
    }
}
