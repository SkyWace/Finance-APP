package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.banksync.BankAccountLink;
import com.financeapp.core.banksync.BankConnection;
import com.financeapp.core.banksync.BankInfo;
import com.financeapp.core.banksync.BankSession;
import com.financeapp.core.banksync.BankSyncCredentials;
import com.financeapp.core.banksync.RemoteAccount;
import com.financeapp.core.banksync.RemoteTransaction;
import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportCandidate;
import com.financeapp.core.imports.ImportedRow;
import com.financeapp.core.port.BankSyncClient;
import com.financeapp.core.port.BankSyncClientFactory;
import com.financeapp.core.port.BankSyncRepository;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synchronisation bancaire (prototype V5, facultative, desactivee par defaut).
 *
 * <p>Lecture seule, via un agregateur agree utilise avec les propres cles de
 * l'utilisateur ; aucun serveur FinanceApp, aucun identifiant bancaire. Les
 * operations recuperees suivent exactement le chemin d'un import de fichier :
 * apercu, detection des doublons (identifiant bancaire), rapprochements,
 * Inbox "a valider", annulation du lot. Rien n'est enregistre sans validation.
 */
public final class BankSyncService {

    /** Consultations maximales par compte sur 24 heures sans l'utilisateur (RTS DSP2, art. 36). */
    public static final int MAX_FETCHES_PER_DAY = 4;
    /** Profondeur de la premiere recuperation. */
    public static final int FIRST_SYNC_DAYS = 90;
    /** Chevauchement des recuperations suivantes (operations comptabilisees en retard). */
    public static final int OVERLAP_DAYS = 5;
    /** Duree maximale d'un consentement AIS (reglement delegue (UE) 2022/2360). */
    public static final int MAX_CONSENT_DAYS = 180;

    /** Operations pretes a etre verifiees dans l'apercu d'import. */
    public record SyncBatch(BankConnection connection, BankAccountLink link, long accountId, List<ImportedRow> rows,
                            List<ImportCandidate> candidates, int pendingSkipped, int otherCurrencySkipped,
                            LocalDate from) {

        public String sourceName() {
            return "Synchronisation — " + connection.bankName() + " — " + link.name();
        }
    }

    /** Etat d'une connexion pour l'affichage. */
    public record ConnectionView(BankConnection connection, List<BankAccountLink> accounts, long daysLeft,
                                 boolean expired) {
    }

    private record Pending(BankInfo bank, Instant validUntil, Instant createdAt) {
    }

    private final BankSyncRepository repository;
    private final BankSyncClientFactory factory;
    private final ImportService imports;
    private final AccountService accounts;
    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile BankSyncClient client;

    public BankSyncService(BankSyncRepository repository, BankSyncClientFactory factory, ImportService imports,
                           AccountService accounts, Clock clock) {
        this.repository = repository;
        this.factory = factory;
        this.imports = imports;
        this.accounts = accounts;
        this.clock = clock;
    }

    // ------------------------------------------------------------ activation

    public boolean isEnabled() {
        return repository.credentials().isPresent();
    }

    public String providerName() {
        return factory.providerName();
    }

    public Optional<BankSyncCredentials> credentials() {
        return repository.credentials();
    }

    /** Active la synchronisation avec les parametres de l'utilisateur (la cle est verifiee). */
    public void enable(BankSyncCredentials credentials) {
        if (credentials.applicationId().isBlank()) {
            throw new BusinessException("Saisissez l'identifiant de l'application (Application ID)");
        }
        checkRedirectUrl(credentials.redirectUrl());
        BankSyncClient created;
        try {
            created = factory.create(credentials);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        repository.saveCredentials(credentials);
        client = created;
    }

    /** Desactive : revoque les sessions (au mieux) et efface parametres, connexions et comptes lies. */
    public void disable() {
        if (isEnabled()) {
            for (BankConnection c : repository.connections()) {
                revokeQuietly(c);
            }
        }
        repository.clearAll();
        pending.clear();
        client = null;
    }

    // ------------------------------------------------------------ connexion

    public List<BankInfo> banks(String country) {
        return call(() -> client().banks(country)).stream()
                .sorted(Comparator.comparing(BankInfo::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Prepare l'autorisation : renvoie l'adresse de la banque a ouvrir dans le
     * navigateur. Le consentement demande est le plus long accepte par la banque,
     * sans depasser {@value #MAX_CONSENT_DAYS} jours.
     */
    public String startLink(BankInfo bank) {
        Instant now = clock.instant();
        int days = bank.maxConsentDays() > 0 ? Math.min(bank.maxConsentDays(), MAX_CONSENT_DAYS) : 90;
        Instant validUntil = now.plus(Duration.ofDays(days));
        String state = UUID.randomUUID().toString();
        String redirect = requireCredentials().redirectUrl();
        String url = call(() -> client().startAuthorization(bank, redirect, state, validUntil));
        if (!url.startsWith("https://")) {
            throw new BusinessException("Adresse d'autorisation inattendue : connexion interrompue");
        }
        pending.values().removeIf(p -> p.createdAt().isBefore(now.minus(Duration.ofHours(1))));
        pending.put(state, new Pending(bank, validUntil, now));
        return url;
    }

    /**
     * Termine l'autorisation a partir de l'adresse affichee par le navigateur
     * apres l'accord donne a la banque (elle contient {@code code} et {@code state}).
     */
    public BankConnection completeLink(String returnedAddress) {
        Map<String, String> query = queryOf(returnedAddress);
        if (query.containsKey("error")) {
            throw new BusinessException("La banque n'a pas donné son accord (" + query.get("error") + ")"
                    + (query.containsKey("error_description") ? " : " + query.get("error_description") : "."));
        }
        String state = query.get("state");
        Pending request = state == null ? null : pending.get(state);
        if (request == null) {
            throw new BusinessException("Cette adresse ne correspond à aucune demande en cours : relancez la connexion "
                    + "de la banque et collez l'adresse obtenue juste après.");
        }
        String code = query.get("code");
        if (code == null || code.isBlank()) {
            throw new BusinessException("Adresse incomplète : copiez toute l'adresse affichée par le navigateur "
                    + "après l'autorisation (elle contient « code= »).");
        }
        BankSession session = call(() -> client().createSession(code));
        pending.remove(state);
        Instant validUntil = session.validUntil() != null ? session.validUntil() : request.validUntil();
        BankConnection connection = new BankConnection(null, session.sessionId(),
                session.bankName() != null ? session.bankName() : request.bank().name(),
                session.country() != null ? session.country() : request.bank().country(), validUntil, clock.instant());
        List<BankAccountLink> links = new ArrayList<>();
        for (RemoteAccount a : session.accounts()) {
            links.add(new BankAccountLink(null, 0, a.uid(), a.name(), a.maskedIban(), a.currency(), null, null, null));
        }
        if (links.isEmpty()) {
            revokeQuietly(connection);
            throw new BusinessException("La banque n'a autorisé aucun compte. En mode restreint, seuls les comptes "
                    + "préalablement liés dans le portail de " + providerName() + " sont accessibles.");
        }
        return repository.saveConnection(connection, links);
    }

    /** Associe un compte bancaire a un compte FinanceApp ({@code null} = ne plus synchroniser). */
    public BankAccountLink assign(long linkId, Long localAccountId) {
        BankAccountLink link = link(linkId);
        if (localAccountId != null) {
            Account account = accounts.get(localAccountId);
            if (link.currency() != null && !link.currency().equals(account.currency().getCurrencyCode())) {
                throw new BusinessException("Le compte « " + account.name() + " » est en "
                        + account.currency().getCurrencyCode() + ", le compte bancaire en " + link.currency());
            }
            repository.links().stream()
                    .filter(l -> !l.id().equals(linkId) && localAccountId.equals(l.localAccountId()))
                    .findFirst()
                    .ifPresent(other -> {
                        throw new BusinessException("Le compte « " + account.name() + " » est déjà alimenté par « "
                                + other.name() + " »");
                    });
        }
        return repository.saveLink(link.withLocalAccount(localAccountId));
    }

    public void disconnect(long connectionId) {
        repository.connections().stream().filter(c -> c.id() == connectionId).findFirst()
                .ifPresent(this::revokeQuietly);
        repository.deleteConnection(connectionId);
    }

    public List<ConnectionView> overview() {
        Instant now = clock.instant();
        Map<Long, List<BankAccountLink>> byConnection = new HashMap<>();
        for (BankAccountLink l : repository.links()) {
            byConnection.computeIfAbsent(l.connectionId(), k -> new ArrayList<>()).add(l);
        }
        return repository.connections().stream()
                .map(c -> new ConnectionView(c, byConnection.getOrDefault(c.id(), List.of()),
                        Math.max(0, Duration.between(now, c.validUntil()).toDays()), c.expired(now)))
                .toList();
    }

    public int fetchesLeftToday(long linkId) {
        return Math.max(0, MAX_FETCHES_PER_DAY - repository.fetchesSince(linkId, clock.instant().minus(Duration.ofDays(1))));
    }

    // ------------------------------------------------------------ recuperation

    /**
     * Recupere les operations comptabilisees depuis la derniere synchronisation
     * (moins {@value #OVERLAP_DAYS} jours de chevauchement ; {@value #FIRST_SYNC_DAYS}
     * jours la premiere fois) et prepare l'apercu. Rien n'est enregistre.
     * Les operations en attente sont ignorees : leur identifiant change souvent a
     * la comptabilisation, elles seront proposees une fois comptabilisees.
     */
    public SyncBatch fetch(long linkId) {
        BankAccountLink link = link(linkId);
        BankConnection connection = repository.connections().stream()
                .filter(c -> c.id() == link.connectionId()).findFirst()
                .orElseThrow(() -> new BusinessException("Connexion introuvable"));
        Instant now = clock.instant();
        if (connection.expired(now)) {
            throw new BusinessException("Le consentement donné à " + connection.bankName() + " a expiré : "
                    + "reconnectez la banque (la réglementation impose un renouvellement au plus tous les "
                    + MAX_CONSENT_DAYS + " jours).");
        }
        if (link.localAccountId() == null) {
            throw new BusinessException("Choisissez d'abord le compte FinanceApp alimenté par « " + link.name() + " »");
        }
        if (fetchesLeftToday(linkId) == 0) {
            throw new BusinessException("« " + link.name() + " » a déjà été consulté " + MAX_FETCHES_PER_DAY
                    + " fois ces dernières 24 heures, la limite fixée par la réglementation. Réessayez plus tard.");
        }
        Account account = accounts.get(link.localAccountId());
        LocalDate today = LocalDate.now(clock);
        LocalDate from = link.syncedUntil() == null ? today.minusDays(FIRST_SYNC_DAYS)
                : link.syncedUntil().minusDays(OVERLAP_DAYS);
        List<RemoteTransaction> remote = call(() -> client().transactions(link.accountUid(), from));
        repository.recordFetch(linkId, now);

        String currency = account.currency().getCurrencyCode();
        List<ImportedRow> rows = new ArrayList<>();
        int pendingSkipped = 0;
        int otherCurrency = 0;
        List<RemoteTransaction> sorted = remote.stream()
                .sorted(Comparator.comparing(RemoteTransaction::date, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (RemoteTransaction t : sorted) {
            if (!t.booked()) {
                pendingSkipped++;
                continue;
            }
            if (t.currency() != null && !t.currency().equals(currency)) {
                otherCurrency++;
                continue;
            }
            int line = rows.size() + 1;
            if (t.date() == null || t.amount() == null) {
                rows.add(ImportedRow.invalid(line, t.label() == null ? "" : t.label(), "Date ou montant absent"));
            } else {
                String label = t.label() == null || t.label().isBlank() ? "Opération bancaire" : t.label();
                rows.add(new ImportedRow(line, t.date(), label, t.amount(), t.externalId(), null));
            }
        }
        List<ImportCandidate> candidates = rows.isEmpty() ? List.of() : imports.plan(account.id(), rows);
        return new SyncBatch(connection, link, account.id(), rows, candidates, pendingSkipped, otherCurrency, from);
    }

    /** Enregistre les lignes validees dans l'apercu (meme chemin qu'un import de fichier). */
    public ImportBatch commit(SyncBatch batch, List<ImportService.Decision> decisions) {
        ImportBatch result = imports.commit(batch.accountId(), batch.sourceName(), ImportService.Format.BANK_SYNC,
                decisions);
        markSynced(batch);
        return result;
    }

    /** Aucune ligne a importer (tout est deja present) : la synchronisation est a jour. */
    public void markSynced(SyncBatch batch) {
        LocalDate latest = batch.rows().stream().map(ImportedRow::date).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        BankAccountLink current = link(batch.link().id());
        LocalDate until = latest == null ? current.syncedUntil()
                : current.syncedUntil() == null || latest.isAfter(current.syncedUntil()) ? latest : current.syncedUntil();
        repository.saveLink(current.withSync(until, clock.instant()));
    }

    /**
     * Repart de {@value #FIRST_SYNC_DAYS} jours a la prochaine recuperation (apres
     * l'annulation d'un lot par exemple). Les operations deja presentes sont
     * reconnues par leur identifiant bancaire et proposees comme doublons.
     */
    public void resync(long linkId) {
        BankAccountLink link = link(linkId);
        repository.saveLink(link.withSync(null, link.lastSyncAt()));
    }

    // ------------------------------------------------------------ outils

    private BankAccountLink link(long id) {
        return repository.link(id).orElseThrow(() -> new BusinessException("Compte bancaire introuvable"));
    }

    private BankSyncCredentials requireCredentials() {
        return repository.credentials().orElseThrow(() -> new BusinessException("La synchronisation bancaire n'est pas activée"));
    }

    private BankSyncClient client() {
        BankSyncClient c = client;
        if (c == null) {
            try {
                c = factory.create(requireCredentials());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(e.getMessage());
            }
            client = c;
        }
        return c;
    }

    private void revokeQuietly(BankConnection c) {
        try {
            client().deleteSession(c.sessionId());
        } catch (RuntimeException ignored) {
            // Revocation au mieux : le consentement expire de toute facon ; la suppression locale suffit.
        }
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    static void checkRedirectUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Adresse de retour invalide");
        }
        boolean loopback = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()) && loopback)) {
            throw new BusinessException("L'adresse de retour doit être en HTTPS (exemple : https://localhost/financeapp), "
                    + "identique à celle déclarée dans le portail de l'agrégateur");
        }
    }

    static Map<String, String> queryOf(String address) {
        if (address == null || address.isBlank()) {
            throw new BusinessException("Collez l'adresse affichée par le navigateur après l'autorisation");
        }
        String text = address.strip();
        int q = text.indexOf('?');
        String query = q >= 0 ? text.substring(q + 1) : text;
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        Map<String, String> result = new HashMap<>();
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                result.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
