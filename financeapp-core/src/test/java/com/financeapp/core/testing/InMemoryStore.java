package com.financeapp.core.testing;

import com.financeapp.core.account.Account;
import com.financeapp.core.category.Category;
import com.financeapp.core.budget.Budget;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.loan.Loan;
import com.financeapp.core.banksync.BankAccountLink;
import com.financeapp.core.banksync.BankConnection;
import com.financeapp.core.banksync.BankSyncCredentials;
import com.financeapp.core.port.BankSyncRepository;
import com.financeapp.core.port.LoanRepository;
import com.financeapp.core.port.SimulationRepository;
import com.financeapp.core.simulation.Simulation;
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

    /** Pour les tests : place une operation dans "A valider", comme un import. */
    public void markNeedsReview(long transactionId) {
        needsReview.add(transactionId);
    }
    private final Map<Long, String> externalIdByTx = new HashMap<>();
    private final Map<Long, Long> batchByTx = new HashMap<>();
    private final Map<Long, ImportBatch> batchMap = new LinkedHashMap<>();
    private final Map<Long, List<Reconciliation>> reconciliationsByBatch = new HashMap<>();
    private final Map<Long, SavingsGoal> goalMap = new LinkedHashMap<>();
    private final Map<Long, Loan> loanMap = new LinkedHashMap<>();
    private final Map<Long, com.financeapp.core.account.AccountValuation> valuationMap = new LinkedHashMap<>();

    private final Map<Long, com.financeapp.core.attachment.Attachment> attachmentMap = new LinkedHashMap<>();
    private final Map<Long, byte[]> attachmentContent = new HashMap<>();

    /** Justificatifs ; ceux d'une operation supprimee disparaissent avec elle (comme la cascade SQL). */
    public final com.financeapp.core.port.AttachmentRepository attachments = new com.financeapp.core.port.AttachmentRepository() {
        private java.util.stream.Stream<com.financeapp.core.attachment.Attachment> live() {
            return attachmentMap.values().stream().filter(a -> transactionMap.containsKey(a.transactionId()));
        }
        public com.financeapp.core.attachment.Attachment insert(com.financeapp.core.attachment.Attachment a, byte[] content) {
            long id = ids.getAndIncrement();
            var saved = new com.financeapp.core.attachment.Attachment(id, a.transactionId(), a.fileName(), a.type(),
                    a.size(), a.addedAt());
            attachmentMap.put(id, saved);
            attachmentContent.put(id, content.clone());
            return saved;
        }
        public List<com.financeapp.core.attachment.Attachment> findByTransaction(long transactionId) {
            return live().filter(a -> a.transactionId() == transactionId).toList();
        }
        public Optional<com.financeapp.core.attachment.Attachment> findById(long id) {
            return live().filter(a -> a.id() == id).findFirst();
        }
        public Optional<byte[]> content(long id) {
            return findById(id).map(a -> attachmentContent.get(id).clone());
        }
        public void delete(long id) {
            attachmentMap.remove(id);
            attachmentContent.remove(id);
        }
        public Map<Long, Integer> countByTransactions(java.util.Collection<Long> transactionIds) {
            Map<Long, Integer> counts = new HashMap<>();
            live().filter(a -> transactionIds.contains(a.transactionId()))
                    .forEach(a -> counts.merge(a.transactionId(), 1, Integer::sum));
            return counts;
        }
        public long countInImportBatch(long batchId) {
            return live().filter(a -> Long.valueOf(batchId).equals(batchByTx.get(a.transactionId()))).count();
        }
        public long[] usage() {
            return new long[]{live().count(), live().mapToLong(com.financeapp.core.attachment.Attachment::size).sum()};
        }
    };

    public final com.financeapp.core.port.ValuationRepository valuations = new com.financeapp.core.port.ValuationRepository() {
        public List<com.financeapp.core.account.AccountValuation> findByAccount(long accountId) {
            return valuationMap.values().stream().filter(v -> v.accountId() == accountId)
                    .sorted(Comparator.comparing(com.financeapp.core.account.AccountValuation::date).reversed()).toList();
        }
        public Map<Long, com.financeapp.core.account.AccountValuation> latestByAccount() {
            Map<Long, com.financeapp.core.account.AccountValuation> latest = new HashMap<>();
            for (var v : valuationMap.values()) {
                latest.merge(v.accountId(), v, (a, b) -> a.date().isAfter(b.date()) ? a : b);
            }
            return latest;
        }
        public com.financeapp.core.account.AccountValuation save(com.financeapp.core.account.AccountValuation v) {
            valuationMap.values().removeIf(o -> o.accountId() == v.accountId() && o.date().equals(v.date()));
            var saved = v.id() == null ? v.withId(ids.getAndIncrement()) : v;
            valuationMap.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) { valuationMap.remove(id); }
    };
    private final Map<Long, Simulation> simulationMap = new LinkedHashMap<>();

    private BankSyncCredentials bankCredentials;
    private final Map<Long, BankConnection> connectionMap = new LinkedHashMap<>();
    private final Map<Long, BankAccountLink> linkMap = new LinkedHashMap<>();
    private final List<Map.Entry<Long, java.time.Instant>> fetches = new ArrayList<>();

    public final BankSyncRepository bankSync = new BankSyncRepository() {
        public Optional<BankSyncCredentials> credentials() { return Optional.ofNullable(bankCredentials); }
        public void saveCredentials(BankSyncCredentials c) { bankCredentials = c; }
        public void clearAll() { bankCredentials = null; connectionMap.clear(); linkMap.clear(); fetches.clear(); }
        public List<BankConnection> connections() { return List.copyOf(connectionMap.values()); }
        public BankConnection saveConnection(BankConnection c, List<BankAccountLink> accounts) {
            BankConnection saved = c.withId(ids.getAndIncrement());
            connectionMap.put(saved.id(), saved);
            for (BankAccountLink l : accounts) {
                BankAccountLink withConnection = new BankAccountLink(ids.getAndIncrement(), saved.id(), l.accountUid(),
                        l.name(), l.maskedIban(), l.currency(), l.localAccountId(), l.syncedUntil(), l.lastSyncAt());
                linkMap.put(withConnection.id(), withConnection);
            }
            return saved;
        }
        public void deleteConnection(long id) { connectionMap.remove(id); linkMap.values().removeIf(l -> l.connectionId() == id); }
        public List<BankAccountLink> links() { return List.copyOf(linkMap.values()); }
        public Optional<BankAccountLink> link(long id) { return Optional.ofNullable(linkMap.get(id)); }
        public BankAccountLink saveLink(BankAccountLink l) { linkMap.put(l.id(), l); return l; }
        public void recordFetch(long linkId, java.time.Instant at) { fetches.add(Map.entry(linkId, at)); }
        public int fetchesSince(long linkId, java.time.Instant since) {
            return (int) fetches.stream().filter(e -> e.getKey() == linkId && !e.getValue().isBefore(since)).count();
        }
    };

    public final LoanRepository loans = new LoanRepository() {
        public List<Loan> findAll() { return List.copyOf(loanMap.values()); }
        public Optional<Loan> findById(long id) { return Optional.ofNullable(loanMap.get(id)); }
        public Loan save(Loan l) {
            Loan saved = l.id() == null ? l.withId(ids.getAndIncrement()) : l;
            loanMap.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) { loanMap.remove(id); }
    };

    public final SimulationRepository simulations = new SimulationRepository() {
        public List<Simulation> findAll() { return List.copyOf(simulationMap.values()); }
        public Optional<Simulation> findById(long id) { return Optional.ofNullable(simulationMap.get(id)); }
        public Simulation save(Simulation s) {
            Simulation saved = s.id() == null ? s.withId(ids.getAndIncrement()) : s;
            simulationMap.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) { simulationMap.remove(id); }
    };

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
            return transactionMap.values().stream().filter(t -> Objects.equals(t.categoryId(), id)
                            || t.splits().stream().anyMatch(l -> Objects.equals(l.categoryId(), id))).count()
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
                    .filter(t -> categories == null || t.categoryShares().stream().anyMatch(l -> categories.contains(l.categoryId())))
                    .filter(t -> q.tagId() == null || t.tagIds().contains(q.tagId()))
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
            // Filtre par categorie : seule la part ventilee dans la categorie compte.
            Set<Long> scope = new java.util.HashSet<>();
            if (q.categoryId() != null) {
                scope.add(q.categoryId());
                categoryMap.values().stream().filter(c -> q.categoryId().equals(c.parentId())).forEach(c -> scope.add(c.id()));
            }
            java.util.function.Function<Transaction, Money> part = t -> q.categoryId() == null ? t.amount()
                    : t.categoryShares().stream().filter(l -> scope.contains(l.categoryId()))
                      .map(com.financeapp.core.transaction.SplitLine::amount).reduce(zero, Money::plus);
            return new SearchTotals(rows.size(), expenses.size(),
                    expenses.stream().map(part).reduce(zero, Money::plus),
                    rows.stream().filter(t -> t.type() == TransactionType.INCOME).map(part).reduce(zero, Money::plus));
        }
        public Map<Long, Long> sumCountedMinorByAccount() {
            return transactionMap.values().stream().filter(t -> t.status().countsInBalance())
                    .collect(Collectors.groupingBy(Transaction::accountId,
                            Collectors.summingLong(t -> t.amount().toMinorUnits())));
        }
        public long sumCountedMinorAfter(long accountId, LocalDate after) {
            return transactionMap.values().stream().filter(t -> t.status().countsInBalance())
                    .filter(t -> t.accountId() == accountId && t.date().isAfter(after))
                    .mapToLong(t -> t.amount().toMinorUnits()).sum();
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

    private final Map<Long, com.financeapp.core.tag.Tag> tagMap = new LinkedHashMap<>();

    public final com.financeapp.core.port.TagRepository tags = new com.financeapp.core.port.TagRepository() {
        public List<com.financeapp.core.tag.Tag> findAll() {
            return tagMap.values().stream().sorted(Comparator.comparing(t -> t.name().toLowerCase())).toList();
        }
        public Optional<com.financeapp.core.tag.Tag> findById(long id) { return Optional.ofNullable(tagMap.get(id)); }
        public Optional<com.financeapp.core.tag.Tag> findByName(String name) {
            return tagMap.values().stream().filter(t -> t.name().equalsIgnoreCase(name.strip())).findFirst();
        }
        public com.financeapp.core.tag.Tag save(com.financeapp.core.tag.Tag tag) {
            com.financeapp.core.tag.Tag saved = tag.id() == null ? tag.withId(ids.getAndIncrement()) : tag;
            tagMap.put(saved.id(), saved);
            return saved;
        }
        public void delete(long id) {
            tagMap.remove(id);
            transactionMap.replaceAll((k, t) -> {
                Set<Long> kept = new java.util.HashSet<>(t.tagIds());
                kept.remove(id);
                return t.withDetails(t.splits(), kept);
            });
        }
        public Map<Long, Long> usageCounts() {
            Map<Long, Long> counts = new HashMap<>();
            transactionMap.values().forEach(t -> t.tagIds().forEach(id -> counts.merge(id, 1L, Long::sum)));
            return counts;
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
