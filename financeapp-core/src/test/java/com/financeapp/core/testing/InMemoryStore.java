package com.financeapp.core.testing;

import com.financeapp.core.account.Account;
import com.financeapp.core.category.Category;
import com.financeapp.core.port.AccountRepository;
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
        public List<Transaction> search(TransactionQuery q) {
            return transactionMap.values().stream()
                    .filter(t -> q.accountId() == null || t.accountId() == q.accountId())
                    .filter(t -> q.from() == null || !t.date().isBefore(q.from()))
                    .filter(t -> q.to() == null || !t.date().isAfter(q.to()))
                    .filter(t -> q.text() == null || t.label().toLowerCase().contains(q.text().toLowerCase()))
                    .filter(t -> q.categoryId() == null || q.categoryId().equals(t.categoryId()))
                    .filter(t -> q.statuses() == null || q.statuses().contains(t.status()))
                    .sorted(Comparator.comparing(Transaction::date).thenComparing(Transaction::id).reversed())
                    .skip(q.offset()).limit(q.limit())
                    .toList();
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

    public List<Transaction> allTransactions() {
        return new ArrayList<>(transactionMap.values());
    }
}
