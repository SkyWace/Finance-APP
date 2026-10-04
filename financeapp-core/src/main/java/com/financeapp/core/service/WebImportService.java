package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.export.Json;
import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportedTransaction;
import com.financeapp.core.imports.Reconciliation;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.ImportRepository;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.core.text.LabelNormalizer;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reprise dans l'application des operations saisies sur la version web (sauvegarde
 * du site, dechiffree par l'infra). Seules les operations absentes sont ajoutees.
 *
 * <p>Principe : <b>rien d'ambigu n'est importe en silence</b>.
 * <ul>
 *   <li>chaque compte du site est associe a un compte de l'application (meme nom par
 *       defaut), cree a l'import, ou ignore ;</li>
 *   <li>une operation venue de l'application (meme identifiant, meme compte, montant ou libelle
 *       identique) est reconnue : inchangee, elle est ignoree ; prevue ici et effectuee sur le
 *       site, elle passe a "effectuee" ; modifiee sur le site, elle est signalee sans etre importee ;</li>
 *   <li>une operation identique (compte, date, montant, libelle) deja presente est ignoree ;
 *       une operation de meme montant a ±{@value #DUPLICATE_DAYS} jours est un doublon
 *       possible, decoche ;</li>
 *   <li>une operation prevue de l'application realisee sur le site (meme montant, libelle
 *       equivalent, ±{@value #MATCH_DAYS} jours) passe a "effectuee", sans creer de doublon ;</li>
 *   <li>une echeance recurrente validee ou ignoree sur le site l'est aussi ici (meme regle,
 *       meme occurrence) : elle n'est pas comptee deux fois dans le disponible reel ;</li>
 *   <li>un virement vers un compte non importe devient une depense ou un revenu, a confirmer.</li>
 * </ul>
 * L'import est enregistre comme un import de releve : il se defait depuis l'ecran Imports.
 */
public final class WebImportService {

    static final int DUPLICATE_DAYS = 3;
    static final int MATCH_DAYS = 10;

    /** Compte du site et compte de l'application propose. */
    public record WebAccount(long webId, String name, AccountType type, LocalDate openingDate, long initialBalance,
                             int operations, Long suggestedAccountId) {
    }

    /** Destination choisie pour un compte du site. */
    public sealed interface Target {
        /** Compte existant de l'application. */
        record Existing(long accountId) implements Target {
        }

        /** Compte a creer (meme nom, type et solde initial que sur le site). */
        record Create() implements Target {
        }

        /** Operations de ce compte non importees. */
        record Skip() implements Target {
        }
    }

    public enum Kind {
        NEW("Nouvelle", true),
        REALIZES_PLANNED("Réalise une opération prévue", true),
        /** Operation venue de l'application puis modifiee sur le site : rien n'est importe (a reporter a la main). */
        MODIFIED("Modifiée sur le site", false),
        TRANSFER_TO_SKIPPED("Virement avec un compte non importé", false),
        POSSIBLE_DUPLICATE("Doublon possible", false),
        ALREADY_PRESENT("Déjà présente", false);

        private final String label;
        private final boolean includedByDefault;

        Kind(String label, boolean includedByDefault) {
            this.label = label;
            this.includedByDefault = includedByDefault;
        }

        public String label() {
            return label;
        }

        public boolean includedByDefault() {
            return includedByDefault;
        }
    }

    /**
     * Operation du site analysee. Pour un virement, une seule ligne (jambe debitrice).
     *
     * @param amount          montant signe, vu du compte {@code webAccountId}
     * @param matched         operation de l'application correspondante (doublon ou operation prevue realisee)
     * @param recurringRuleId regle de l'application dont l'operation realise ou ignore une occurrence
     */
    public record Item(int index, Kind kind, long webAccountId, Long webToAccountId, LocalDate date, String label,
                       long amount, TransactionType type, TransactionStatus status, Long webCategoryId, String category,
                       String note, List<String> tags, Long recurringRuleId, LocalDate occurrenceDate,
                       Transaction matched) {
    }

    /** Apercu : comptes du site, operations analysees, nombre d'operations ignorees d'office. */
    public record Plan(List<WebAccount> accounts, List<Item> items, int skippedAccountOperations, int cancelled) {

        public long count(Kind kind) {
            return items.stream().filter(i -> i.kind() == kind).count();
        }
    }

    /** Compte rendu : operations creees, operations prevues realisees, comptes et categories crees. */
    public record Result(int created, int realized, int accountsCreated, int categoriesCreated, List<Long> batchIds) {
    }

    private final AccountService accounts;
    private final CategoryService categories;
    private final TransactionRepository transactions;
    private final RecurringService recurring;
    private final TagService tags;
    private final ImportRepository imports;
    private final SettingsService settings;

    public WebImportService(AccountService accounts, CategoryService categories, TransactionRepository transactions,
                            RecurringService recurring, TagService tags, ImportRepository imports,
                            SettingsService settings) {
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.recurring = recurring;
        this.tags = tags;
        this.imports = imports;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ lecture des donnees du site

    private record WebCategory(long id, Long parentId, String name, CategoryKind kind, String code) {
    }

    private record WebTx(long id, long accountId, LocalDate date, String label, long amount, TransactionType type,
                         TransactionStatus status, Long categoryId, String note, List<String> tags,
                         String transferGroup, Long transferAccountId, Long recurringId, LocalDate occurrenceDate) {
    }

    private record WebRule(long id, long accountId, String label) {
    }

    private record WebData(List<WebAccount> accounts, Map<Long, WebCategory> categories, List<WebTx> transactions,
                           Map<Long, WebRule> rules) {
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> l)) {
            throw new BusinessException("Fichier du site illisible : « " + key + " » inattendu");
        }
        return (List<Map<String, Object>>) l;
    }

    private static String text(Map<String, Object> o, String key) {
        return o.get(key) instanceof String s ? s : null;
    }

    private static Long number(Map<String, Object> o, String key) {
        return o.get(key) instanceof Long l ? l : null;
    }

    private static long required(Map<String, Object> o, String key) {
        Long value = number(o, key);
        if (value == null) {
            throw new BusinessException("Fichier du site illisible : « " + key + " » manquant");
        }
        return value;
    }

    private static LocalDate date(Map<String, Object> o, String key) {
        String s = text(o, key);
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException("Fichier du site illisible : date « " + s + " » invalide");
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, E fallback) {
        try {
            return value == null ? fallback : Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private WebData read(String dataJson) {
        Object parsed;
        try {
            parsed = Json.parse(dataJson);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Fichier du site illisible : " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> m) || !Long.valueOf(1).equals(((Map<String, Object>) m).get("version"))) {
            throw new BusinessException("Ce fichier ne contient pas des données de la version web (version inconnue)");
        }
        Map<String, Object> root = (Map<String, Object>) parsed;

        Map<Long, WebCategory> cats = new HashMap<>();
        for (Map<String, Object> c : list(root, "categories")) {
            cats.put(required(c, "id"), new WebCategory(required(c, "id"), number(c, "parentId"),
                    Objects.requireNonNullElse(text(c, "name"), "?"),
                    enumOf(CategoryKind.class, text(c, "kind"), CategoryKind.EXPENSE), text(c, "code")));
        }
        Map<Long, WebRule> rules = new HashMap<>();
        for (Map<String, Object> r : list(root, "rules")) {
            rules.put(required(r, "id"), new WebRule(required(r, "id"), required(r, "accountId"),
                    Objects.requireNonNullElse(text(r, "label"), "")));
        }
        List<WebTx> txs = new ArrayList<>();
        Map<Long, Integer> perAccount = new HashMap<>();
        for (Map<String, Object> t : list(root, "transactions")) {
            List<String> tagNames = new ArrayList<>();
            if (t.get("tags") instanceof List<?> l) {
                l.forEach(x -> {
                    if (x instanceof String s && !s.isBlank()) {
                        tagNames.add(s);
                    }
                });
            }
            WebTx tx = new WebTx(required(t, "id"), required(t, "accountId"), date(t, "date"),
                    Objects.requireNonNullElse(text(t, "label"), "").strip(), required(t, "amount"),
                    enumOf(TransactionType.class, text(t, "type"), TransactionType.EXPENSE),
                    enumOf(TransactionStatus.class, text(t, "status"), TransactionStatus.COMPLETED),
                    number(t, "categoryId"), text(t, "note"), tagNames, text(t, "transferGroup"),
                    number(t, "transferAccountId"), number(t, "recurringId"), date(t, "occurrenceDate"));
            if (tx.date() == null || tx.label().isEmpty() || tx.amount() == 0) {
                continue; // ligne incomplete : le site ne permet pas de la saisir
            }
            txs.add(tx);
            perAccount.merge(tx.accountId(), 1, Integer::sum);
        }
        Currency currency = settings.baseCurrency();
        List<Account> mine = accounts.findAll().stream().filter(a -> a.currency().equals(currency)).toList();
        List<WebAccount> webAccounts = new ArrayList<>();
        for (Map<String, Object> a : list(root, "accounts")) {
            String name = Objects.requireNonNullElse(text(a, "name"), "Compte").strip();
            Long suggested = mine.stream().filter(x -> x.name().strip().equalsIgnoreCase(name)).map(Account::id)
                    .findFirst().orElse(null);
            long id = required(a, "id");
            webAccounts.add(new WebAccount(id, name, enumOf(AccountType.class, text(a, "type"), AccountType.OTHER),
                    Optional.ofNullable(date(a, "openingDate")).orElse(LocalDate.now()),
                    Optional.ofNullable(number(a, "initialBalance")).orElse(0L),
                    perAccount.getOrDefault(id, 0), suggested));
        }
        return new WebData(webAccounts, cats, txs, rules);
    }

    // ------------------------------------------------------------------ apercu

    /** Associations par defaut : meme nom, sinon compte non importe. */
    public static Map<Long, Target> defaultTargets(List<WebAccount> webAccounts) {
        Map<Long, Target> targets = new LinkedHashMap<>();
        for (WebAccount a : webAccounts) {
            targets.put(a.webId(), a.suggestedAccountId() != null ? new Target.Existing(a.suggestedAccountId())
                    : new Target.Skip());
        }
        return targets;
    }

    /** Comptes du site (et leurs associations proposees), pour la premiere etape. */
    public List<WebAccount> accounts(String dataJson) {
        return read(dataJson).accounts();
    }

    /**
     * Analyse les operations du site par rapport a celles de l'application.
     *
     * @param targets destination de chaque compte du site (absent = non importe)
     */
    public Plan plan(String dataJson, Map<Long, Target> targets) {
        WebData data = read(dataJson);
        Map<Long, String> categoryNames = new HashMap<>();
        data.categories().values().forEach(c -> categoryNames.put(c.id(), webPath(c, data.categories())));

        // Operations de l'application, par compte, consommees au fur et a mesure (une seule correspondance chacune)
        List<Transaction> existing = new ArrayList<>(allTransactions());
        Map<Long, Transaction> byId = new HashMap<>();
        existing.forEach(e -> byId.put(e.id(), e));
        Map<String, Transaction> occurrences = new HashMap<>();
        for (Transaction t : existing) {
            if (t.recurringId() != null) {
                // Une echeance de virement validee porte la regle sur ses deux jambes : la premiere suffit.
                occurrences.putIfAbsent(t.recurringId() + "|" + t.occurrenceDate(), t);
            }
        }
        Map<Long, RecurringRule> myRules = new HashMap<>();
        recurring.findAll().forEach(r -> myRules.put(r.id(), r));

        // Un virement = deux jambes : on garde la jambe debitrice (ou la seule presente)
        Map<String, WebTx> creditLegs = new HashMap<>();
        List<WebTx> primary = new ArrayList<>();
        for (WebTx t : data.transactions()) {
            if (t.type() == TransactionType.TRANSFER && t.transferGroup() != null) {
                if (t.amount() > 0) {
                    creditLegs.put(t.transferGroup(), t);
                    continue;
                }
            }
            primary.add(t);
        }
        Set<String> debitGroups = new java.util.HashSet<>();
        primary.stream().filter(t -> t.transferGroup() != null).forEach(t -> debitGroups.add(t.transferGroup()));
        creditLegs.values().stream().filter(t -> !debitGroups.contains(t.transferGroup())).forEach(primary::add);
        primary.sort(Comparator.comparing(WebTx::date).thenComparing(WebTx::id));

        List<Item> items = new ArrayList<>();
        int skippedAccounts = 0;
        int cancelled = 0;
        int index = 0;
        for (WebTx t : primary) {
            Long accountId = destination(targets.get(t.accountId()), t.accountId());
            if (accountId == null) {
                skippedAccounts++;
                continue;
            }
            Long linkedRule = linkedRule(t, data.rules(), myRules);
            Transaction matched = null;
            if (t.status() == TransactionStatus.CANCELLED && linkedRule == null) {
                cancelled++; // operation annulee sans effet sur les soldes
                continue;
            }
            boolean transfer = t.type() == TransactionType.TRANSFER;
            Long toWeb = transfer ? t.transferAccountId() : null;
            Long toAccount = transfer ? destination(targets.get(toWeb), toWeb) : null;
            TransactionType type = t.type();
            Kind kind = Kind.NEW;
            String note = t.note();
            if (transfer && (toAccount == null || toAccount.equals(accountId))) {
                type = t.amount() < 0 ? TransactionType.EXPENSE : TransactionType.INCOME;
                String other = data.accounts().stream().filter(a -> a.webId() == Objects.requireNonNullElse(toWeb, -1L))
                        .map(WebAccount::name).findFirst().orElse("un autre compte");
                note = append(note, "Virement " + (t.amount() < 0 ? "vers " : "depuis ") + other + " (compte non importé)");
                kind = Kind.TRANSFER_TO_SKIPPED;
            }
            Money amount = Money.ofMinor(t.amount(), settings.baseCurrency());

            Transaction sameOccurrence = linkedRule == null ? null
                    : occurrences.get(linkedRule + "|" + t.occurrenceDate());
            if (sameOccurrence != null) {
                // Echeance deja validee ou ignoree dans l'application
                existing.remove(sameOccurrence);
                occurrences.remove(linkedRule + "|" + t.occurrenceDate());
                matched = sameOccurrence;
                kind = sameOccurrence.accountId() == accountId && sameOccurrence.amount().equals(amount)
                        && sameOccurrence.status() == t.status() ? Kind.ALREADY_PRESENT : Kind.POSSIBLE_DUPLICATE;
                linkedRule = null; // importee quand meme (choix explicite) : sans lien, l'occurrence est deja traitee
            } else if (sameOperation(byId.get(t.id()), accountId, amount, t) && existing.contains(byId.get(t.id()))) {
                // Operation exportee par l'application (identifiant conserve par le site)
                matched = byId.get(t.id());
                existing.remove(matched);
                boolean unchanged = matched.amount().equals(amount) && matched.date().equals(t.date())
                        && LabelNormalizer.normalize(matched.label()).equals(LabelNormalizer.normalize(t.label()));
                if (unchanged && matched.status() == t.status()) {
                    kind = Kind.ALREADY_PRESENT;
                } else if (matched.status() == TransactionStatus.PLANNED && t.status().countsInBalance()
                        && matched.amount().equals(amount)) {
                    kind = Kind.REALIZES_PLANNED;
                } else {
                    kind = Kind.MODIFIED;
                }
                linkedRule = null;
            } else {
                String label = LabelNormalizer.normalize(t.label());
                Optional<Transaction> exact = existing.stream()
                        .filter(e -> e.accountId() == accountId && e.amount().equals(amount) && e.date().equals(t.date())
                                && LabelNormalizer.normalize(e.label()).equals(label))
                        .findFirst();
                Optional<Transaction> near = existing.stream()
                        .filter(e -> e.accountId() == accountId && e.amount().equals(amount)
                                && e.status() != TransactionStatus.PLANNED && e.status() != TransactionStatus.CANCELLED
                                && days(e.date(), t.date()) <= DUPLICATE_DAYS)
                        .min(Comparator.comparingLong(e -> days(e.date(), t.date())));
                Optional<Transaction> planned = existing.stream()
                        .filter(e -> e.accountId() == accountId && e.status() == TransactionStatus.PLANNED
                                && e.amount().equals(amount) && days(e.date(), t.date()) <= MATCH_DAYS
                                && similar(label, LabelNormalizer.normalize(e.label())))
                        .min(Comparator.comparingLong(e -> days(e.date(), t.date())));
                if (exact.isPresent()) {
                    matched = exact.get();
                    kind = matched.status() == t.status() ? Kind.ALREADY_PRESENT
                            : matched.status() == TransactionStatus.PLANNED && t.status().countsInBalance()
                            ? Kind.REALIZES_PLANNED : Kind.POSSIBLE_DUPLICATE;
                } else if (near.isPresent()) {
                    matched = near.get();
                    kind = Kind.POSSIBLE_DUPLICATE;
                } else if (planned.isPresent()) {
                    // Operation prevue de l'application : realisee sur le site, ou simplement redatee
                    matched = planned.get();
                    kind = t.status().countsInBalance() ? Kind.REALIZES_PLANNED : Kind.POSSIBLE_DUPLICATE;
                }
                if (matched != null) {
                    existing.remove(matched);
                }
            }
            String category = type == TransactionType.TRANSFER || t.categoryId() == null ? null
                    : categoryNames.get(t.categoryId());
            boolean simple = type != t.type();
            items.add(new Item(index++, kind, t.accountId(), simple ? null : toWeb, t.date(), t.label(), t.amount(), type,
                    t.status(), type == TransactionType.TRANSFER ? null : t.categoryId(), category, note, t.tags(),
                    linkedRule, linkedRule == null ? null : t.occurrenceDate(), matched));
        }
        return new Plan(data.accounts(), items, skippedAccounts, cancelled);
    }

    /** Meme operation que celle de l'application portant cet identifiant : meme compte, meme montant ou libelle. */
    private static boolean sameOperation(Transaction mine, long accountId, Money amount, WebTx t) {
        return mine != null && mine.accountId() == accountId && (mine.amount().equals(amount)
                || LabelNormalizer.normalize(mine.label()).equals(LabelNormalizer.normalize(t.label())));
    }

    /** Regle de l'application realisee par l'operation : meme identifiant et meme libelle (regle exportee). */
    private static Long linkedRule(WebTx t, Map<Long, WebRule> webRules, Map<Long, RecurringRule> myRules) {
        if (t.recurringId() == null || t.occurrenceDate() == null) {
            return null;
        }
        WebRule webRule = webRules.get(t.recurringId());
        RecurringRule mine = myRules.get(t.recurringId());
        if (webRule == null || mine == null || !LabelNormalizer.normalize(webRule.label())
                .equals(LabelNormalizer.normalize(mine.label()))) {
            return null; // regle creee sur le site : l'operation est importee comme une operation simple
        }
        return mine.id();
    }

    private Long destination(Target target, Long webAccountId) {
        if (target == null || webAccountId == null) {
            return null;
        }
        return switch (target) {
            case Target.Existing e -> e.accountId();
            case Target.Create c -> -webAccountId; // compte a creer : identifiant provisoire negatif
            case Target.Skip s -> null;
        };
    }

    private List<Transaction> allTransactions() {
        List<Transaction> all = new ArrayList<>();
        for (int offset = 0; ; offset += 500) {
            List<Transaction> page = transactions.search(new TransactionQuery(null, null, null, null, null, null,
                    500, offset));
            all.addAll(page);
            if (page.size() < 500) {
                return all;
            }
        }
    }

    // ------------------------------------------------------------------ enregistrement

    /**
     * Enregistre les operations choisies, atomiquement par compte (un lot d'import par compte,
     * annulable depuis l'ecran Imports). Les comptes "a creer" et les categories inconnues
     * sont crees d'abord.
     *
     * @param included index des operations a importer
     */
    public Result commit(String dataJson, Map<Long, Target> targets, Set<Integer> included, String fileName) {
        Plan plan = plan(dataJson, targets);
        WebData data = read(dataJson);
        Currency currency = settings.baseCurrency();

        List<Item> chosen = plan.items().stream()
                .filter(i -> included.contains(i.index()) && i.kind() != Kind.ALREADY_PRESENT && i.kind() != Kind.MODIFIED)
                .toList();
        if (chosen.isEmpty()) {
            return new Result(0, 0, 0, 0, List.of());
        }

        // Comptes a creer (seulement ceux qui recoivent des operations)
        Map<Long, Long> created = new HashMap<>();
        Set<Long> needed = new LinkedHashSet<>();
        chosen.forEach(i -> {
            needed.add(i.webAccountId());
            if (i.webToAccountId() != null) {
                needed.add(i.webToAccountId());
            }
        });
        for (WebAccount a : plan.accounts()) {
            if (needed.contains(a.webId()) && targets.get(a.webId()) instanceof Target.Create) {
                String name = uniqueAccountName(a.name());
                Account account = accounts.save(Account.create(name, a.type(), Money.ofMinor(a.initialBalance(), currency),
                        a.openingDate()));
                created.put(a.webId(), account.id());
            }
        }

        CategoryResolver resolver = new CategoryResolver(data.categories());
        Map<Long, List<ImportedTransaction>> byAccount = new LinkedHashMap<>();
        Map<Long, List<Reconciliation>> reconciledByAccount = new LinkedHashMap<>();
        int realized = 0;
        int count = 0;
        for (Item i : chosen) {
            long accountId = resolve(targets.get(i.webAccountId()), i.webAccountId(), created);
            Money amount = Money.ofMinor(i.amount(), currency);
            if (i.kind() == Kind.REALIZES_PLANNED) {
                Transaction p = i.matched();
                reconciledByAccount.computeIfAbsent(accountId, k -> new ArrayList<>())
                        .add(new Reconciliation(p.id(), i.date(), p.label(), p.status(), p.date(), p.label()));
                realized++;
                continue;
            }
            Long categoryId = i.type() == TransactionType.TRANSFER ? null : resolver.resolve(i.webCategoryId());
            Set<Long> tagIds = i.tags().isEmpty() ? Set.of() : tags.resolve(i.tags());
            List<ImportedTransaction> list = byAccount.computeIfAbsent(accountId, k -> new ArrayList<>());
            if (i.type() == TransactionType.TRANSFER) {
                long to = resolve(targets.get(i.webToAccountId()), i.webToAccountId(), created);
                String group = UUID.randomUUID().toString();
                list.add(new ImportedTransaction(new Transaction(null, accountId, i.date(), i.label(), amount,
                        TransactionType.TRANSFER, i.status(), null, i.note(), group, to, i.recurringRuleId(),
                        i.occurrenceDate(), List.of(), Set.of()), null));
                list.add(new ImportedTransaction(new Transaction(null, to, i.date(), i.label(), amount.negate(),
                        TransactionType.TRANSFER, i.status(), null, i.note(), group, accountId, i.recurringRuleId(),
                        i.occurrenceDate(), List.of(), Set.of()), null));
            } else {
                list.add(new ImportedTransaction(new Transaction(null, accountId, i.date(), i.label(), amount, i.type(),
                        i.status(), categoryId, i.note(), null, null, i.recurringRuleId(), i.occurrenceDate(),
                        List.of(), tagIds), null));
            }
            count++;
        }

        List<Long> batches = new ArrayList<>();
        Set<Long> touched = new LinkedHashSet<>(byAccount.keySet());
        touched.addAll(reconciledByAccount.keySet());
        int skipped = plan.items().size() - chosen.size();
        boolean first = true;
        for (Long accountId : touched) {
            List<ImportedTransaction> ops = byAccount.getOrDefault(accountId, List.of());
            List<Reconciliation> recs = reconciledByAccount.getOrDefault(accountId, List.of());
            long createdHere = ops.stream().filter(o -> o.transaction().type() != TransactionType.TRANSFER
                    || o.transaction().amount().isNegative()).count();
            ImportBatch batch = new ImportBatch(null, accountId, fileName, ImportService.Format.WEB.name(), Instant.now(),
                    (int) createdHere, recs.size(), first ? skipped : 0, false);
            first = false;
            batches.add(imports.commit(batch, ops, recs, false).id());
        }
        return new Result(count, realized, created.size(), resolver.created, batches);
    }

    private static long resolve(Target target, long webAccountId, Map<Long, Long> created) {
        return switch (target) {
            case Target.Existing e -> e.accountId();
            case Target.Create c -> created.get(webAccountId);
            case null, default -> throw new BusinessException("Compte de destination manquant");
        };
    }

    private String uniqueAccountName(String name) {
        Set<String> names = new java.util.HashSet<>();
        accounts.findAll().forEach(a -> names.add(a.name().toLowerCase(Locale.ROOT)));
        String candidate = name;
        for (int n = 2; names.contains(candidate.toLowerCase(Locale.ROOT)); n++) {
            candidate = name + " (" + n + ")";
        }
        return candidate;
    }

    /** Categorie du site → categorie de l'application : code stable, sinon chemin, sinon creee. */
    private final class CategoryResolver {
        private final Map<Long, WebCategory> web;
        private final Map<Long, Long> resolved = new HashMap<>();
        int created;

        CategoryResolver(Map<Long, WebCategory> web) {
            this.web = web;
        }

        Long resolve(Long webId) {
            if (webId == null || !web.containsKey(webId)) {
                return null;
            }
            Long known = resolved.get(webId);
            if (known != null) {
                return known;
            }
            WebCategory c = web.get(webId);
            List<Category> mine = categories.findAll();
            Long parentId = c.parentId() == null ? null : resolve(c.parentId());
            Optional<Category> match = Optional.empty();
            if (c.code() != null) {
                match = mine.stream().filter(m -> c.code().equals(m.systemCode())).findFirst();
            }
            if (match.isEmpty()) {
                match = mine.stream().filter(m -> Objects.equals(m.parentId(), parentId)
                        && m.name().strip().equalsIgnoreCase(c.name().strip())).findFirst();
            }
            long id;
            if (match.isPresent()) {
                id = match.get().id();
            } else {
                id = categories.create(parentId, c.name().strip(), parentId == null ? c.kind() : null).id();
                created++;
            }
            resolved.put(webId, id);
            return id;
        }
    }

    private static String webPath(WebCategory c, Map<Long, WebCategory> all) {
        WebCategory parent = c.parentId() == null ? null : all.get(c.parentId());
        return parent == null ? c.name() : parent.name() + " › " + c.name();
    }

    private static long days(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    private static boolean similar(String a, String b) {
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.equals(b) || LabelNormalizer.containsWords(a, b) || LabelNormalizer.containsWords(b, a);
    }

    private static String append(String note, String text) {
        return note == null || note.isBlank() ? text : note + " · " + text;
    }
}
