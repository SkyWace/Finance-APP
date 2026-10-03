package com.financeapp.infra.security;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
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

import javax.crypto.AEADBadTagException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class WebExportTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    /** Accents et symbole : la derivation doit etre identique a celle du navigateur (NFC, UTF-8). */
    static final String PASSWORD = "Épargne sûre 2026 €";

    @TempDir
    Path dir;

    private static String field(String json, String regex) {
        Matcher m = Pattern.compile(regex).matcher(json);
        assertTrue(m.find(), regex);
        return m.group(1);
    }

    @Test
    void exportsTheProfileForTheWebVersionEncrypted() throws Exception {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        Account checking = db.accounts.save(Account.create("Compte courant", AccountType.CHECKING, Money.eur("1000"),
                LocalDate.of(2026, 1, 1)));
        Account livret = db.accounts.save(Account.create("Livret A", AccountType.LIVRET_A, Money.eur("5000"),
                LocalDate.of(2026, 1, 1)));
        Account swiss = db.accounts.save(Account.create("Compte suisse", AccountType.CHECKING,
                Money.of(new BigDecimal("300"), Currency.getInstance("CHF")), LocalDate.of(2026, 1, 1)));
        Category home = db.categories.create(null, "Maison test", CategoryKind.EXPENSE);
        Category food = db.categories.create(null, "Nourriture test", CategoryKind.EXPENSE);
        Set<Long> tags = db.tags.resolve(List.of("vacances"));

        db.transactions.create(new TransactionDraft(checking.id(), LocalDate.of(2026, 10, 1), "Courses \"Bio\"",
                new BigDecimal("120"), TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, "ligne 1\nligne 2",
                List.of(new TransactionDraft.Split(food.id(), new BigDecimal("90")),
                        new TransactionDraft.Split(home.id(), new BigDecimal("30"))), tags));
        db.transactions.create(new TransactionDraft(checking.id(), LocalDate.of(2026, 10, 20), "Prévu",
                new BigDecimal("15"), TransactionType.EXPENSE, TransactionStatus.PLANNED, null, null, List.of(), Set.of()));
        db.transactions.createTransfer(new TransferDraft(checking.id(), livret.id(), LocalDate.of(2026, 10, 2),
                "Épargne", new BigDecimal("100"), TransactionStatus.COMPLETED, null));
        db.transactions.create(new TransactionDraft(swiss.id(), LocalDate.of(2026, 10, 2), "Fondue",
                new BigDecimal("50"), TransactionType.EXPENSE, TransactionStatus.COMPLETED, null, null, List.of(), Set.of()));
        db.accounts.recordValuation(livret.id(), LocalDate.of(2026, 9, 30), new BigDecimal("5200"));
        db.recurring.save(new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE, "Loyer",
                Money.eur("700"), home.id(), Frequency.MONTHLY, 1, LocalDate.of(2026, 11, 5), null, null, true, true,
                null, List.of(), tags));

        WebExportService.Export export = new WebExportService(db.accounts, db.categories, db.transactionRepo,
                db.recurring, db.tags, db.settings).export();
        WebExportService.Report r = export.report();
        assertEquals(2, r.accounts());
        assertEquals(1, r.skippedAccounts(), "compte en francs suisses");
        assertEquals(1, r.skippedTransactions(), "operation du compte suisse");
        assertEquals(4, r.transactions());
        assertEquals(1, r.rules());
        assertEquals(1, r.splitsSimplified());

        String data = export.json();
        // Solde actuel conserve : courant 1000 - 120 - 100 = 780 ; livret 5200 (valeur saisie) + 100 = 5300
        assertTrue(data.contains("\"name\":\"Compte courant\",\"type\":\"CHECKING\",\"initialBalance\":100000"), data);
        assertTrue(data.contains("\"name\":\"Livret A\",\"type\":\"LIVRET_A\",\"initialBalance\":520000"), data);
        assertTrue(data.contains("\"label\":\"Courses \\\"Bio\\\"\""));
        assertTrue(data.contains("ligne 1\\nligne 2 · Ventilée : Nourriture test (90,00) + Maison test (30,00)"));
        assertFalse(data.contains("Fondue"));
        assertTrue(data.contains("\"parentId\":null"));
        assertTrue(data.contains("\"tags\":[\"vacances\"]"));

        WebBackupWriter.Result result = new WebBackupWriter().write("Profil principal", data, PASSWORD.toCharArray());
        String json = result.json();
        assertTrue(result.recoveryKey().matches("([A-Z2-9]{4}-){7}[A-Z2-9]{4}"));
        assertFalse(json.contains("Courses"), "chiffre");
        assertFalse(json.contains(PASSWORD));
        assertTrue(json.startsWith("{\"format\":\"financeapp-web-backup\",\"version\":1,"));

        String id = field(json, "\"profile\":\\{\"id\":\"([^\"]+)\"");
        String password = json.substring(json.indexOf("\"password\":"));
        String payload = json.substring(json.indexOf("\"payload\":"));
        String decrypted = WebBackupWriter.readPayload(id, field(password, "\"salt\":\"([^\"]+)\""),
                Integer.parseInt(field(password, "\"iterations\":(\\d+)")), field(password, "\"iv\":\"([^\"]+)\""),
                field(password, "\"data\":\"([^\"]+)\""), field(payload, "\"iv\":\"([^\"]+)\""),
                field(payload, "\"data\":\"([^\"]+)\""), PASSWORD.toCharArray());
        assertEquals(data, decrypted);
        assertThrows(AEADBadTagException.class, () -> WebBackupWriter.readPayload(id,
                field(password, "\"salt\":\"([^\"]+)\""), 600_000, field(password, "\"iv\":\"([^\"]+)\""),
                field(password, "\"data\":\"([^\"]+)\""), field(payload, "\"iv\":\"([^\"]+)\""),
                field(payload, "\"data\":\"([^\"]+)\""), "pas le bon mot de passe".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> new WebBackupWriter().write("x", data, "court".toCharArray()));

        // Fichier relu par les tests de la version web (interoperabilite WebCrypto)
        Path fixture = Path.of("target", "web-export-fixture.json");
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, json + "\n" + result.recoveryKey() + "\n", StandardCharsets.UTF_8);
    }
}
