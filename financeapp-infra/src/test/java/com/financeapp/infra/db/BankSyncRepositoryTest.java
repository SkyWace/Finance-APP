package com.financeapp.infra.db;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.banksync.BankAccountLink;
import com.financeapp.core.banksync.BankConnection;
import com.financeapp.core.banksync.BankSyncCredentials;
import com.financeapp.core.money.Money;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.security.EncryptedDataSource;
import com.financeapp.infra.storage.AppDirectories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BankSyncRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");

    @TempDir
    Path dir;

    @Test
    void migrationKeepsExistingImportBatchesAndTheirLinks() {
        AppDirectories dirs = new AppDirectories(dir).createAll();
        DatabaseKey key = new DatabaseKey();
        key.unlock(SqliteTestDb.randomKey());
        EncryptedDataSource ds = new EncryptedDataSource(dirs.databaseFile(), key);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("5").load().migrate();
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO accounts (id, name, type, initial_balance_minor, currency, opening_date, include_in_available,"
                + " archived, sort_order, created_at, updated_at) VALUES (1, 'CC', 'CHECKING', 0, 'EUR', '2026-01-01', 1, 0, 0,"
                + " '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')").update();
        jdbc.sql("INSERT INTO import_batches (id, account_id, file_name, format, imported_at, created) "
                + "VALUES (7, 1, 'releve.csv', 'CSV', '2026-09-01T10:00:00Z', 1)").update();
        jdbc.sql("INSERT INTO transactions (id, account_id, date, label, amount_minor, type, status, "
                + "import_batch_id, created_at, updated_at) VALUES (3, 1, '2026-08-30', 'CB TEST', -1000, 'EXPENSE', "
                + "'COMPLETED', 7, '2026-09-01T10:00:00Z', '2026-09-01T10:00:00Z')").update();
        jdbc.sql("INSERT INTO import_reconciliations (batch_id, transaction_id, previous_status, previous_date, previous_label)"
                + " VALUES (7, 3, 'PLANNED', '2026-08-29', 'Test')").update();

        new DatabaseMigrator(ds).migrate();

        assertEquals("CSV", jdbc.sql("SELECT format FROM import_batches WHERE id = 7").query(String.class).single());
        assertEquals(7L, jdbc.sql("SELECT import_batch_id FROM transactions WHERE id = 3").query(Long.class).single(),
                "les cles etrangeres vers le lot sont intactes");
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM import_reconciliations").query(Long.class).single());
        jdbc.sql("INSERT INTO import_batches (account_id, file_name, format, imported_at) "
                + "VALUES (1, 'sync', 'BANK_SYNC', '2026-09-30T10:00:00Z')").update();
        assertThrows(RuntimeException.class, () -> jdbc.sql("INSERT INTO import_batches (account_id, file_name, format, "
                + "imported_at) VALUES (1, 'x', 'XLS', '2026-09-30T10:00:00Z')").update(), "le CHECK est conserve");
        assertTrue(jdbc.sql("PRAGMA foreign_key_check").query((rs, i) -> rs.getString(1)).list().isEmpty());
    }

    @Test
    void settingsConnectionsLinksAndFetchCounting() {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        JdbcBankSyncRepository repo = db.bankSyncRepo;
        Account checking = db.accounts.save(Account.create("CC", AccountType.CHECKING, Money.eur("0"), TODAY));
        Account livret = db.accounts.save(Account.create("Livret", AccountType.PASSBOOK, Money.eur("0"), TODAY));

        assertTrue(repo.credentials().isEmpty());
        repo.saveCredentials(new BankSyncCredentials("app", "PEM-1", "https://localhost/financeapp"));
        repo.saveCredentials(new BankSyncCredentials("app", "PEM-2", "https://localhost/financeapp"));
        assertEquals("PEM-2", repo.credentials().orElseThrow().privateKeyPem());

        BankConnection c = repo.saveConnection(new BankConnection(null, "sess-1", "Banque", "FR",
                NOW.plusSeconds(86400 * 180L), NOW), List.of(
                new BankAccountLink(null, 0, "uid-1", "Compte chèque", "FR76 •••• 0001", "EUR", null, null, null),
                new BankAccountLink(null, 0, "uid-2", "Livret A", null, "EUR", null, null, null)));
        assertEquals(c, repo.connections().getFirst());
        List<BankAccountLink> links = repo.links();
        assertEquals(2, links.size());

        BankAccountLink first = repo.saveLink(links.get(0).withLocalAccount(checking.id())
                .withSync(LocalDate.of(2026, 9, 29), NOW));
        assertEquals(first, repo.link(first.id()).orElseThrow());
        assertThrows(RuntimeException.class, () -> repo.saveLink(links.get(1).withLocalAccount(checking.id())),
                "un compte FinanceApp, un seul compte bancaire");
        repo.saveLink(links.get(1).withLocalAccount(livret.id()));

        repo.recordFetch(first.id(), NOW.minusSeconds(90000));
        repo.recordFetch(first.id(), NOW.minusSeconds(60));
        repo.recordFetch(first.id(), NOW);
        assertEquals(2, repo.fetchesSince(first.id(), NOW.minusSeconds(86400)));

        repo.deleteConnection(c.id());
        assertTrue(repo.links().isEmpty(), "cascade");
        assertEquals(0L, db.jdbc.sql("SELECT COUNT(*) FROM bank_sync_fetches").query(Long.class).single());
        repo.clearAll();
        assertTrue(repo.credentials().isEmpty());
    }
}
