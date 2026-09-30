package com.financeapp.core.banksync;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportCandidate;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.BankSyncService;
import com.financeapp.core.service.BusinessException;
import com.financeapp.core.service.ImportService;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Synchronisation bancaire (prototype V5) contre un agregateur simule. Aujourd'hui : 30/09/2026. */
class BankSyncServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final Instant NOW = TODAY.atStartOfDay().toInstant(ZoneOffset.UTC);
    private static final BankSyncCredentials CREDENTIALS = new BankSyncCredentials("app-id",
            "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----", "https://localhost/financeapp");

    private final TestApp app = new TestApp(TODAY);
    private Account checking;

    @BeforeEach
    void setUp() {
        checking = app.account("Compte courant", AccountType.CHECKING, "1000");
        app.bank.sessionValidUntil = NOW.plus(Duration.ofDays(180));
    }

    private static RemoteTransaction booked(String id, String date, String amount, String label) {
        return new RemoteTransaction(id, LocalDate.parse(date), new BigDecimal(amount), "EUR", label, true);
    }

    private BankAccountLink connect() {
        app.bankSync.enable(CREDENTIALS);
        BankInfo bank = app.bankSync.banks("FR").getLast();
        app.bankSync.startLink(bank);
        app.bankSync.completeLink("https://localhost/financeapp?code=code-123&state=" + app.bank.lastState);
        BankAccountLink link = app.store.bankSync.links().getFirst();
        return app.bankSync.assign(link.id(), checking.id());
    }

    private ImportBatch importAll(BankSyncService.SyncBatch batch) {
        return app.bankSync.commit(batch, batch.candidates().stream()
                .map(c -> new ImportService.Decision(c, c.includedByDefault(),
                        c.suggestion() == null ? null : c.suggestion().categoryId()))
                .toList());
    }

    @Test
    void disabledByDefaultAndEnablingChecksTheSettings() {
        assertFalse(app.bankSync.isEnabled());
        assertThrows(BusinessException.class, () -> app.bankSync.enable(new BankSyncCredentials("app", "k",
                "http://example.com/retour")), "HTTP hors boucle locale refuse");
        assertThrows(BusinessException.class, () -> app.bankSync.enable(new BankSyncCredentials("app", "INVALID",
                "https://localhost/financeapp")), "cle illisible");
        assertThrows(BusinessException.class, () -> app.bankSync.enable(new BankSyncCredentials(" ", "k",
                "https://localhost/financeapp")));
        assertFalse(app.bankSync.isEnabled());

        app.bankSync.enable(CREDENTIALS);
        assertTrue(app.bankSync.isEnabled());
        assertFalse(CREDENTIALS.toString().contains("secret"), "la cle n'apparait jamais dans un toString / journal");
    }

    @Test
    void linkingUsesTheBankConsentDurationAndChecksTheState() {
        app.bankSync.enable(CREDENTIALS);
        List<BankInfo> banks = app.bankSync.banks("FR");
        assertEquals("Alpha Banque", banks.getFirst().name(), "tri alphabetique");

        String url = app.bankSync.startLink(banks.get(1));
        assertTrue(url.startsWith("https://"));
        assertEquals(NOW.plus(Duration.ofDays(180)), app.bank.lastValidUntil, "180 jours au plus");
        assertEquals("https://localhost/financeapp", app.bank.lastRedirect);
        app.bankSync.startLink(banks.getFirst());
        assertEquals(NOW.plus(Duration.ofDays(90)), app.bank.lastValidUntil, "limite propre a la banque");
        String state = app.bank.lastState;

        assertThrows(BusinessException.class, () -> app.bankSync.completeLink(
                "https://localhost/financeapp?code=code-123&state=autre"), "etat inconnu : adresse d'une autre demande");
        BusinessException refused = assertThrows(BusinessException.class, () -> app.bankSync.completeLink(
                "https://localhost/financeapp?error=access_denied&state=" + state));
        assertTrue(refused.getMessage().contains("access_denied"));
        assertThrows(BusinessException.class, () -> app.bankSync.completeLink(
                "https://localhost/financeapp?state=" + state), "code absent");

        BankConnection c = app.bankSync.completeLink("  https://localhost/financeapp?code=code-123&state=" + state + "  ");
        assertEquals("Zeta Banque", c.bankName());
        assertEquals(1, app.store.bankSync.links().size());
        assertThrows(BusinessException.class, () -> app.bankSync.completeLink(
                "https://localhost/financeapp?code=code-123&state=" + state), "une adresse ne sert qu'une fois");
    }

    @Test
    void accountsMustBeAssignedToAMatchingLocalAccount() {
        app.bankSync.enable(CREDENTIALS);
        app.bankSync.startLink(app.bankSync.banks("FR").getFirst());
        app.bankSync.completeLink("https://localhost/financeapp?code=code-123&state=" + app.bank.lastState);
        BankAccountLink link = app.store.bankSync.links().getFirst();

        assertThrows(BusinessException.class, () -> app.bankSync.fetch(link.id()), "compte FinanceApp non choisi");
        Account usd = app.accounts.save(Account.create("Compte USD", AccountType.CHECKING,
                Money.of("0", java.util.Currency.getInstance("USD")), TODAY));
        assertThrows(BusinessException.class, () -> app.bankSync.assign(link.id(), usd.id()));
        app.bankSync.assign(link.id(), checking.id());

        app.bank.accounts = List.of(new RemoteAccount("uid-2", "Livret", "FR76 •••• 0002", "EUR"));
        app.bankSync.startLink(app.bankSync.banks("FR").getFirst());
        app.bankSync.completeLink("https://localhost/financeapp?code=code-123&state=" + app.bank.lastState);
        BankAccountLink second = app.store.bankSync.links().getLast();
        assertThrows(BusinessException.class, () -> app.bankSync.assign(second.id(), checking.id()),
                "un compte FinanceApp n'est alimente que par un compte bancaire");
    }

    @Test
    void syncFollowsTheImportPipelineAndNeverImportsTwice() {
        app.monthly(checking, TransactionType.EXPENSE, "Loyer", "650", LocalDate.of(2026, 7, 3));
        app.bank.transactions.addAll(List.of(
                booked("T1", "2026-09-26", "-56.42", "CB STATION TOTAL"),
                booked("T2", "2026-09-29", "-650.00", "PRLV SEPA LOYER OCTOBRE"),
                new RemoteTransaction("P1", LocalDate.parse("2026-09-30"), new BigDecimal("-12.00"), "EUR", "CB CAFE", false),
                new RemoteTransaction("X1", LocalDate.parse("2026-09-28"), new BigDecimal("-20.00"), "USD", "AMAZON US", true),
                booked("T0", "2026-05-01", "-5.00", "TROP ANCIEN")));
        BankAccountLink link = connect();

        BankSyncService.SyncBatch first = app.bankSync.fetch(link.id());
        assertEquals(TODAY.minusDays(90), first.from(), "premiere synchronisation : 90 jours");
        assertEquals(1, first.pendingSkipped(), "operations en attente ignorees");
        assertEquals(1, first.otherCurrencySkipped());
        assertEquals(2, first.rows().size());
        assertEquals(ImportCandidate.Kind.NEW, first.candidates().get(0).kind());
        assertEquals(ImportCandidate.Kind.MATCHES_RECURRING, first.candidates().get(1).kind(),
                "le loyer preleve realise l'echeance, comme pour un import de fichier");
        assertTrue(app.store.allTransactions().isEmpty(), "rien n'est enregistre avant validation");

        ImportBatch batch = importAll(first);
        assertEquals("BANK_SYNC", batch.format());
        assertEquals(2, app.inbox.count(), "les operations arrivent dans « A valider »");
        assertEquals(Money.eur("293.58"), app.accounts.balanceOf(checking.id()));

        app.bank.transactions.add(booked("T3", "2026-09-30", "-8.40", "CB BOULANGERIE"));
        BankSyncService.SyncBatch second = app.bankSync.fetch(link.id());
        assertEquals(LocalDate.of(2026, 9, 24), second.from(), "reprise a la derniere operation moins 5 jours");
        assertEquals(List.of(ImportCandidate.Kind.DUPLICATE, ImportCandidate.Kind.DUPLICATE, ImportCandidate.Kind.NEW),
                second.candidates().stream().map(ImportCandidate::kind).toList(), "identifiant bancaire deja importe");
        importAll(second);
        assertEquals(Money.eur("285.18"), app.accounts.balanceOf(checking.id()));

        app.bankSync.resync(link.id());
        BankSyncService.SyncBatch again = app.bankSync.fetch(link.id());
        assertTrue(again.candidates().stream().allMatch(c -> c.kind() == ImportCandidate.Kind.DUPLICATE));
    }

    @Test
    void atMostFourFetchesPerDayAndConsentExpiry() {
        BankAccountLink link = connect();
        for (int i = 0; i < BankSyncService.MAX_FETCHES_PER_DAY; i++) {
            app.bankSync.fetch(link.id());
        }
        assertEquals(0, app.bankSync.fetchesLeftToday(link.id()));
        BusinessException e = assertThrows(BusinessException.class, () -> app.bankSync.fetch(link.id()));
        assertTrue(e.getMessage().contains("4 fois"));

        app.bank.sessionValidUntil = NOW;
        app.bank.accounts = List.of(new RemoteAccount("uid-9", "Autre", null, "EUR"));
        Account other = app.account("Autre", AccountType.CHECKING, "0");
        app.bankSync.startLink(app.bankSync.banks("FR").getFirst());
        app.bankSync.completeLink("https://localhost/financeapp?code=code-123&state=" + app.bank.lastState);
        BankAccountLink expired = app.bankSync.assign(app.store.bankSync.links().getLast().id(), other.id());
        BusinessException ex = assertThrows(BusinessException.class, () -> app.bankSync.fetch(expired.id()));
        assertTrue(ex.getMessage().contains("expiré"));
        assertTrue(app.bankSync.overview().getLast().expired());
    }

    @Test
    void consentWithoutAccountsIsRevoked() {
        app.bank.accounts = List.of();
        app.bankSync.enable(CREDENTIALS);
        app.bankSync.startLink(app.bankSync.banks("FR").getFirst());
        BusinessException e = assertThrows(BusinessException.class, () -> app.bankSync.completeLink(
                "https://localhost/financeapp?code=code-123&state=" + app.bank.lastState));
        assertTrue(e.getMessage().contains("mode restreint"));
        assertEquals(List.of("session-1"), app.bank.deletedSessions);
        assertTrue(app.bankSync.overview().isEmpty());
    }

    @Test
    void disablingRevokesSessionsAndErasesEverything() {
        connect();
        app.bankSync.disable();
        assertFalse(app.bankSync.isEnabled());
        assertEquals(List.of("session-1"), app.bank.deletedSessions);
        assertTrue(app.store.bankSync.connections().isEmpty());
        assertTrue(app.store.bankSync.links().isEmpty());
    }
}
