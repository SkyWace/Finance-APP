package com.financeapp.infra.db;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.TransferDraft;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.security.EncryptedDataSource;
import com.financeapp.infra.storage.AppDirectories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SavingsRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @TempDir
    Path dir;

    @Test
    void migrationKeepsExistingAccountsAndAcceptsSavingsTypes() {
        AppDirectories dirs = new AppDirectories(dir).createAll();
        DatabaseKey key = new DatabaseKey();
        key.unlock(SqliteTestDb.randomKey());
        EncryptedDataSource ds = new EncryptedDataSource(dirs.databaseFile(), key);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("6").load().migrate();
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO accounts (id, name, type, initial_balance_minor, currency, opening_date, include_in_available,"
                + " archived, sort_order, created_at, updated_at) VALUES (4, 'Livret', 'PASSBOOK', 380000, 'EUR', '2026-01-01',"
                + " 0, 0, 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')").update();
        jdbc.sql("INSERT INTO transactions (account_id, date, label, amount_minor, type, status, created_at, updated_at)"
                + " VALUES (4, '2026-02-01', 'Frais', -100, 'EXPENSE', 'COMPLETED', '2026-02-01T00:00:00Z', '2026-02-01T00:00:00Z')")
                .update();

        new com.financeapp.infra.db.DatabaseMigrator(ds).migrate();

        assertEquals("PASSBOOK", jdbc.sql("SELECT type FROM accounts WHERE id = 4").query(String.class).single());
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = 4").query(Long.class).single());
        jdbc.sql("UPDATE accounts SET type = 'LIVRET_A' WHERE id = 4").update();
        assertThrows(RuntimeException.class, () -> jdbc.sql("UPDATE accounts SET type = 'LINGOTS' WHERE id = 4").update(),
                "le CHECK est conserve");
        assertTrue(jdbc.sql("PRAGMA foreign_key_check").query((rs, i) -> rs.getString(1)).list().isEmpty());
    }

    @Test
    void valuationsDriveTheBalanceOnSqlite() {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY);
        Account checking = db.accounts.save(Account.create("CC", AccountType.CHECKING, Money.eur("1000"), TODAY.minusYears(1)));
        Account pea = db.accounts.save(Account.create("PEA", AccountType.PEA, Money.eur("5000"), TODAY.minusYears(1)));
        db.transactions.createTransfer(new TransferDraft(checking.id(), pea.id(), LocalDate.of(2026, 9, 10), "Versement",
                new BigDecimal("300"), TransactionStatus.COMPLETED, null));
        assertEquals(Money.eur("5300"), db.accounts.balanceOf(pea.id()));

        db.accounts.recordValuation(pea.id(), LocalDate.of(2026, 9, 15), new BigDecimal("5420.50"));
        db.transactions.createTransfer(new TransferDraft(checking.id(), pea.id(), LocalDate.of(2026, 9, 20), "Versement",
                new BigDecimal("100"), TransactionStatus.COMPLETED, null));
        assertEquals(Money.eur("5520.50"), db.accounts.balanceOf(pea.id()));

        db.accounts.recordValuation(pea.id(), LocalDate.of(2026, 9, 15), new BigDecimal("5400"));
        db.accounts.recordValuation(pea.id(), LocalDate.of(2026, 6, 30), new BigDecimal("5100"));
        assertEquals(2, db.valuationRepo.findByAccount(pea.id()).size());
        assertEquals(LocalDate.of(2026, 9, 15), db.valuationRepo.findByAccount(pea.id()).getFirst().date());
        assertEquals(Money.eur("5500"), db.accounts.balanceOf(pea.id()));
        assertEquals(1, db.valuationRepo.latestByAccount().size());

        assertThrows(RuntimeException.class, () -> db.accounts.delete(pea.id()), "un compte avec operations s'archive");
    }
}
