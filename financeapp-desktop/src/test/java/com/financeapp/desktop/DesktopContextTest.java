package com.financeapp.desktop;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.AccountService;
import com.financeapp.core.service.DashboardService;
import com.financeapp.infra.backup.BackupService;
import com.financeapp.infra.security.Argon2Params;
import com.financeapp.infra.security.DatabaseEncryption;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.security.VaultService;
import com.financeapp.infra.storage.AppDirectories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifie le cablage Spring complet (base SQLite chiffree reelle, migrations, services, verrouillage)
 * sans lancer l'interface JavaFX.
 */
class DesktopContextTest {

    @TempDir
    Path dir;

    @Test
    void contextStartsCreatesTheDatabaseAndServesTheDashboard() throws Exception {
        AppDirectories dirs = new AppDirectories(dir).createAll();
        VaultService vault = new VaultService(dirs.keystoreFile(), dirs.databaseFile(), dirs.backupsDir(),
                new Argon2Params(1024, 1, 1));
        DatabaseKey key = new DatabaseKey();
        key.unlock(vault.create("mot de passe de test".toCharArray()).dek());
        try (ConfigurableApplicationContext ctx = DesktopApplication.start(dirs, key)) {
            AppProperties props = ctx.getBean(AppProperties.class);
            assertEquals("FinanceApp", props.name(), "nom centralise dans application.properties");
            assertEquals("financeapp", props.id());

            ctx.getBean(AccountService.class).save(
                    Account.create("Compte courant", AccountType.CHECKING, Money.eur("1420"), LocalDate.now()));
            var summary = ctx.getBean(DashboardService.class).summary();
            assertEquals(Money.eur("1420"), summary.netWorth());
            assertEquals(Money.eur("1420"), summary.available().available());

            var backup = ctx.getBean(BackupService.class).createAutomaticBackup(3);
            assertEquals(1, backup.accounts());

            key.lock();
            assertThrows(RuntimeException.class, () -> ctx.getBean(AccountService.class).findAll(),
                    "verrouillee : plus aucun acces aux donnees");
            key.unlock(vault.unlock("mot de passe de test".toCharArray()));
            assertEquals(1, ctx.getBean(AccountService.class).findAll().size());
        }
        assertTrue(Files.exists(dirs.databaseFile()));
        assertFalse(DatabaseEncryption.isPlaintextSqlite(dirs.databaseFile()), "base chiffree sur le disque");
        assertTrue(Files.exists(dirs.logsDir().resolve("financeapp.log")));
    }
}
