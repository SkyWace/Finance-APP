package com.financeapp.infra.security;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.infra.db.SqliteTestDb;
import com.financeapp.infra.storage.AppDirectories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class VaultServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private static final Argon2Params FAST = new Argon2Params(1024, 1, 1);
    private static final char[] PASSWORD = "cheval correct batterie".toCharArray();

    @TempDir
    Path dir;
    private AppDirectories dirs;
    private VaultService vault;

    @BeforeEach
    void setUp() {
        dirs = new AppDirectories(dir).createAll();
        vault = new VaultService(dirs.keystoreFile(), dirs.databaseFile(), dirs.backupsDir(), FAST);
    }

    @Test
    void createThenUnlock() throws Exception {
        assertEquals(VaultService.Status.NEW, vault.status());
        VaultService.Creation created = vault.create(PASSWORD);
        assertEquals(VaultService.Status.LOCKED, vault.status());
        assertEquals(32, created.dek().length);
        assertEquals(39, created.recoveryKey().length, "32 caracteres en 8 groupes de 4");

        assertArrayEquals(created.dek(), vault.unlock(PASSWORD));
        InvalidSecretException e = assertThrows(InvalidSecretException.class,
                () -> vault.unlock("cheval correct batteriE".toCharArray()));
        assertEquals("Mot de passe incorrect", e.getMessage());
    }

    @Test
    void keystoreNeverContainsThePasswordNorTheKey() throws Exception {
        VaultService.Creation created = vault.create(PASSWORD);
        String content = Files.readString(dirs.keystoreFile(), StandardCharsets.ISO_8859_1);
        assertFalse(content.contains("cheval"));
        assertFalse(content.contains(java.util.Base64.getEncoder().encodeToString(created.dek())));
        assertFalse(content.toUpperCase().contains(hex(created.dek())));
        assertTrue(content.contains("kdf=argon2id"));
    }

    @Test
    void passwordPolicy() {
        assertThrows(IllegalArgumentException.class, () -> vault.create("court".toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> vault.create("aaaaaaaaaaaa".toCharArray()));
        assertFalse(Files.exists(dirs.keystoreFile()));
    }

    @Test
    void changePasswordKeepsTheSameDatabaseKey() throws Exception {
        byte[] dek = vault.create(PASSWORD).dek();
        assertThrows(InvalidSecretException.class, () -> vault.changePassword("faux mot de passe".toCharArray(), "nouveau mot de passe".toCharArray()));

        vault.changePassword(PASSWORD, "nouveau mot de passe".toCharArray());

        assertThrows(InvalidSecretException.class, () -> vault.unlock(PASSWORD));
        assertArrayEquals(dek, vault.unlock("nouveau mot de passe".toCharArray()), "la base n'a pas a etre rechiffree");
    }

    @Test
    void recoveryKeyResetsAForgottenPassword() throws Exception {
        VaultService.Creation created = vault.create(PASSWORD);
        String recovery = new String(created.recoveryKey());

        assertThrows(InvalidSecretException.class, () -> vault.recover("AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG-HHHH", "nouveau mot de passe".toCharArray()));
        assertThrows(InvalidSecretException.class, () -> vault.recover("trop court", "nouveau mot de passe".toCharArray()));

        // Saisie tolerante : minuscules, espaces au lieu de tirets
        byte[] dek = vault.recover(recovery.toLowerCase().replace('-', ' '), "nouveau mot de passe".toCharArray());
        assertArrayEquals(created.dek(), dek);
        assertArrayEquals(created.dek(), vault.unlock("nouveau mot de passe".toCharArray()));
        assertThrows(InvalidSecretException.class, () -> vault.unlock(PASSWORD));
    }

    @Test
    void regeneratingTheRecoveryKeyInvalidatesTheOldOne() throws Exception {
        VaultService.Creation created = vault.create(PASSWORD);
        String old = new String(created.recoveryKey());
        String fresh = new String(vault.regenerateRecoveryKey(PASSWORD));
        assertNotEquals(old, fresh);
        assertThrows(InvalidSecretException.class, () -> vault.recover(old, "nouveau mot de passe".toCharArray()));
        assertArrayEquals(created.dek(), vault.recover(fresh, "nouveau mot de passe".toCharArray()));
    }

    @Test
    void tamperedKeystoreIsRejected() throws Exception {
        vault.create(PASSWORD);
        String content = Files.readString(dirs.keystoreFile());
        Files.writeString(dirs.keystoreFile(), content.replaceFirst("keyId=\\S+", "keyId=autre-identifiant"));
        assertThrows(InvalidSecretException.class, () -> vault.unlock(PASSWORD), "l'identifiant est lie a l'enveloppe (AAD)");
    }

    @Test
    void plaintextDataFromVersion1IsEncryptedWhenThePasswordIsCreated() throws Exception {
        // Base V1 en clair, en WAL, avec une sauvegarde V1 en clair
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + dirs.databaseFile()); var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
        }
        // Construit un schema complet en clair via Flyway sur une source sans chiffrement
        org.flywaydb.core.Flyway.configure().dataSource("jdbc:sqlite:" + dirs.databaseFile(), null, null)
                .locations("classpath:db/migration").load().migrate();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + dirs.databaseFile())) {
            try (var s = c.createStatement()) {
                s.execute("INSERT INTO accounts (name, type, currency, initial_balance_minor, opening_date, created_at, updated_at) "
                        + "VALUES ('Compte secret', 'CHECKING', 'EUR', 148234, '2026-01-01', 'x', 'x')");
            }
            // Volume realiste (plusieurs centaines de pages) : le cas simple masquait un rekey ignore.
            try (var s = c.createStatement()) {
                s.execute("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 3000) "
                        + "INSERT INTO transactions (account_id, date, label, amount_minor, type, status, created_at, updated_at) "
                        + "SELECT 1, '2026-01-01', 'Carrefour ' || i, -i, 'EXPENSE', 'COMPLETED', 'x', 'x' FROM n");
            }
            try (var s = c.createStatement()) {
                s.execute("VACUUM INTO '" + dirs.backupsDir().resolve("financeapp-auto-20260101-000000.db") + "'");
            }
        }
        assertEquals(VaultService.Status.PLAINTEXT_DATA, vault.status());

        byte[] dek = vault.create(PASSWORD).dek();

        assertFalse(DatabaseEncryption.isPlaintextSqlite(dirs.databaseFile()));
        assertFalse(Files.exists(dirs.databaseFile().resolveSibling("financeapp.db-wal")));
        assertFalse(new String(Files.readAllBytes(dirs.databaseFile()), StandardCharsets.ISO_8859_1).contains("Compte secret"));
        Path oldBackup = dirs.backupsDir().resolve("financeapp-auto-20260101-000000.db");
        assertFalse(DatabaseEncryption.isPlaintextSqlite(oldBackup), "les anciennes sauvegardes sont chiffrees aussi");
        assertTrue(Files.exists(oldBackup.resolveSibling(oldBackup.getFileName() + ".key")));

        SqliteTestDb db = new SqliteTestDb(dir, TODAY, dek);
        Account account = db.accounts.findAll().getFirst();
        assertEquals("Compte secret", account.name());
        assertEquals(Money.eur("1482.34"), account.initialBalance());
        assertEquals(3000, db.transactionRepo.count());
    }

    @Test
    void missingKeystoreIsDetectedAndCanBeImportedFromABackup() throws Exception {
        byte[] dek = vault.create(PASSWORD).dek();
        SqliteTestDb db = new SqliteTestDb(dir, TODAY, dek);
        db.accounts.save(Account.create("Compte", AccountType.CHECKING, Money.eur("1"), TODAY));
        Path savedKeystore = dir.resolve("copie.db.key");
        Files.copy(dirs.keystoreFile(), savedKeystore);

        Files.delete(dirs.keystoreFile());
        assertEquals(VaultService.Status.KEYSTORE_MISSING, vault.status());

        vault.importKeystore(savedKeystore);
        assertArrayEquals(dek, vault.unlock(PASSWORD));
    }

    @Test
    void lockedKeyRefusesConnections() throws Exception {
        byte[] dek = vault.create(PASSWORD).dek();
        SqliteTestDb db = new SqliteTestDb(dir, TODAY, dek);
        db.accounts.save(Account.create("Compte", AccountType.CHECKING, Money.eur("1"), TODAY));

        db.key.lock();
        assertThrows(DatabaseLockedException.class, () -> db.dataSource.getConnection());
        assertThrows(RuntimeException.class, () -> db.accounts.findAll());

        db.key.unlock(vault.unlock(PASSWORD));
        assertEquals(1, db.accounts.findAll().size());
    }

    @Test
    void wrongKeyCannotReadTheDatabase() throws Exception {
        SqliteTestDb db = new SqliteTestDb(dir, TODAY, SqliteTestDb.randomKey());
        db.accounts.save(Account.create("Compte", AccountType.CHECKING, Money.eur("1"), TODAY));
        assertThrows(java.sql.SQLException.class,
                () -> EncryptedDataSource.open(dirs.databaseFile(), SqliteTestDb.randomKey(), true)
                        .createStatement().executeQuery("SELECT count(*) FROM accounts"));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02X", x));
        }
        return sb.toString();
    }

    @Test
    void theLoadedDriverReallySupportsEncryption() {
        assertFalse(DatabaseEncryption.requireCipherSupport().isBlank());
    }
}
