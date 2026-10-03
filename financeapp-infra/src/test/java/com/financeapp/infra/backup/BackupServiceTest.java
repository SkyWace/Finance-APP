package com.financeapp.infra.backup;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.infra.db.SqliteTestDb;
import com.financeapp.infra.security.Argon2Params;
import com.financeapp.infra.security.DatabaseEncryption;
import com.financeapp.infra.security.VaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    static final Argon2Params FAST = new Argon2Params(1024, 1, 1);

    @TempDir
    Path dir;
    private SqliteTestDb db;
    private VaultService vault;
    private final AtomicLong seconds = new AtomicLong(Instant.parse("2026-09-29T10:00:00Z").getEpochSecond());
    private BackupService backups;

    /** Horloge qui avance d'une seconde a chaque lecture : noms de fichiers distincts et ordonnes. */
    private final Clock tickingClock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochSecond(seconds.getAndIncrement()); }
    };

    @BeforeEach
    void setUp() throws Exception {
        Path root = dir.resolve("app");
        var dirs = new com.financeapp.infra.storage.AppDirectories(root).createAll();
        vault = new VaultService(dirs.keystoreFile(), dirs.databaseFile(), dirs.backupsDir(), FAST);
        byte[] dek = vault.create("mot de passe maître".toCharArray()).dek();
        db = new SqliteTestDb(root, TODAY, dek);
        db.accounts.save(Account.create("Compte courant", AccountType.CHECKING, Money.eur("100"), TODAY));
        backups = new BackupService(db.dataSource, db.dirs, db.key, db.migrator.latestKnownVersion(), tickingClock);
    }

    @Test
    void backupsAreEncryptedAndCarryTheirKeystore() throws Exception {
        BackupInfo info = backups.exportTo(dir.resolve("export.db"));
        assertFalse(DatabaseEncryption.isPlaintextSqlite(info.file()));
        assertFalse(new String(Files.readAllBytes(info.file()), "ISO-8859-1").contains("Compte courant"));
        assertTrue(Files.exists(dir.resolve("export.db.key")));
        assertEquals(1, info.accounts());
        assertThrows(Exception.class, () -> DriverManager.getConnection("jdbc:sqlite:" + info.file())
                .createStatement().executeQuery("SELECT count(*) FROM accounts"), "illisible sans la cle");
    }

    @Test
    void automaticBackupsRotateWithoutTouchingManualOnes() throws Exception {
        Path manual = backups.exportTo(db.dirs.backupsDir().resolve("manuelle.db")).file();
        for (int i = 0; i < 5; i++) {
            backups.createAutomaticBackup(3);
        }

        List<BackupInfo> all = backups.listBackups();
        assertEquals(3, all.stream().filter(BackupInfo::automatic).count());
        assertTrue(Files.exists(manual));
        assertTrue(all.stream().allMatch(BackupInfo::sameKey));
        assertTrue(all.stream().filter(BackupInfo::automatic)
                        .allMatch(b -> b.file().getFileName().toString().compareTo("financeapp-auto-20260929-100002") > 0),
                "ce sont les plus anciennes qui ont ete supprimees");
        try (var files = Files.list(db.dirs.backupsDir())) {
            List<String> names = files.map(f -> f.getFileName().toString()).toList();
            assertTrue(names.stream().noneMatch(n -> n.endsWith(".tmp")), "aucun fichier temporaire residuel");
            assertEquals(4, names.stream().filter(n -> n.endsWith(".db.key")).count(), "un .key par sauvegarde, rotation comprise");
        }
    }

    @Test
    void invalidFilesAreRejected() throws Exception {
        Path garbage = dir.resolve("pas-une-base.db");
        Files.writeString(garbage, "ceci n'est pas une base SQLite");
        assertThrows(InvalidBackupException.class, () -> backups.scheduleRestore(garbage, null));

        Path otherDb = dir.resolve("autre.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + otherDb); var s = c.createStatement()) {
            s.execute("CREATE TABLE foo (id INTEGER)");
        }
        assertThrows(InvalidBackupException.class, () -> backups.scheduleRestore(otherDb, null));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void backupFromANewerSchemaIsRejected() throws Exception {
        Path export = backups.exportTo(dir.resolve("export.db")).file();
        BackupService olderApp = new BackupService(db.dataSource, db.dirs, db.key, "1", tickingClock);
        InvalidBackupException e = assertThrows(InvalidBackupException.class, () -> olderApp.scheduleRestore(export, null));
        assertTrue(e.getMessage().contains("plus récente"));
    }

    @Test
    void restoreIsAppliedAtNextStartupWithARawSafetyCopy() throws Exception {
        Path export = backups.exportTo(dir.resolve("export.db")).file();
        db.accounts.save(Account.create("Livret", AccountType.PASSBOOK, Money.eur("50"), TODAY));
        assertEquals(2, db.accounts.findAll().size());

        assertEquals(BackupService.RestoreRequirement.NONE, backups.restoreRequirement(export));
        backups.scheduleRestore(export, null);
        assertTrue(backups.hasPendingRestore());

        byte[] dek = db.key.copy();
        db.key.lock(); // "fermeture" de l'application
        assertTrue(BackupService.applyPendingRestore(db.dirs, tickingClock));
        assertFalse(backups.hasPendingRestore());

        SqliteTestDb reopened = new SqliteTestDb(db.dirs.root(), TODAY, dek);
        assertEquals(1, reopened.accounts.findAll().size());
        try (var files = Files.list(db.dirs.backupsDir())) {
            Path safety = files.filter(f -> f.getFileName().toString().startsWith(BackupService.SAFETY_PREFIX))
                    .findFirst().orElseThrow();
            assertTrue(Files.isDirectory(safety));
            assertTrue(Files.exists(safety.resolve("keystore.properties")));
            // L'etat remplace (base + WAL copies ensemble) reste lisible avec la cle
            var check = new com.financeapp.infra.storage.AppDirectories(dir.resolve("safety-check")).createAll();
            for (String name : List.of("financeapp.db", "financeapp.db-wal", "financeapp.db-shm")) {
                if (Files.exists(safety.resolve(name))) {
                    Files.copy(safety.resolve(name), check.databaseFile().resolveSibling(name));
                }
            }
            assertEquals(2, new SqliteTestDb(check.root(), TODAY, dek).accounts.findAll().size());
        }
        assertFalse(BackupService.applyPendingRestore(db.dirs, tickingClock));
    }

    @Test
    void backupFromAnotherInstallationNeedsItsOwnPasswordAndBringsItsKeystore() throws Exception {
        // Autre installation, autre cle, autre mot de passe
        Path otherRoot = dir.resolve("autre-installation");
        var otherDirs = new com.financeapp.infra.storage.AppDirectories(otherRoot).createAll();
        VaultService otherVault = new VaultService(otherDirs.keystoreFile(), otherDirs.databaseFile(), otherDirs.backupsDir(), FAST);
        byte[] otherDek = otherVault.create("autre mot de passe".toCharArray()).dek();
        SqliteTestDb other = new SqliteTestDb(otherRoot, TODAY, otherDek);
        other.accounts.save(Account.create("A", AccountType.CHECKING, Money.eur("1"), TODAY));
        other.accounts.save(Account.create("B", AccountType.CHECKING, Money.eur("2"), TODAY));
        other.accounts.save(Account.create("C", AccountType.CHECKING, Money.eur("3"), TODAY));
        Path foreign = new BackupService(other.dataSource, other.dirs, other.key, other.migrator.latestKnownVersion(), tickingClock)
                .exportTo(dir.resolve("autre.db")).file();

        assertEquals(BackupService.RestoreRequirement.BACKUP_PASSWORD, backups.restoreRequirement(foreign));
        assertThrows(InvalidBackupException.class, () -> backups.scheduleRestore(foreign, null));
        assertThrows(InvalidBackupException.class, () -> backups.scheduleRestore(foreign, "mauvais mot de passe".toCharArray()));
        assertFalse(backups.hasPendingRestore());

        BackupInfo info = backups.scheduleRestore(foreign, "autre mot de passe".toCharArray());
        assertEquals(3, info.accounts());

        db.key.lock();
        BackupService.applyPendingRestore(db.dirs, tickingClock);
        // Le trousseau de la sauvegarde a remplace le trousseau local : on deverrouille avec SON mot de passe.
        byte[] dek = vault.unlock("autre mot de passe".toCharArray());
        assertArrayEquals(otherDek, dek);
        assertEquals(3, new SqliteTestDb(db.dirs.root(), TODAY, dek).accounts.findAll().size());
        assertThrows(Exception.class, () -> vault.unlock("mot de passe maître".toCharArray()));
    }

    @Test
    void lockedApplicationCannotBackUp() {
        db.key.lock();
        assertThrows(java.io.IOException.class, () -> backups.createAutomaticBackup(3));
    }

    @Test
    void versionComparison() {
        assertTrue(BackupService.compareVersions("10", "9") > 0);
        assertEquals(0, BackupService.compareVersions("2", "2.0"));
        assertTrue(BackupService.compareVersions("1.1", "2") < 0);
    }

    @Test
    void attachmentsAreInTheBackupAndComeBackWithTheRestore() throws Exception {
        var account = db.accounts.findAll().getFirst();
        var t = db.transactions.create(new com.financeapp.core.service.TransactionDraft(account.id(), TODAY, "Facture",
                new java.math.BigDecimal("80"), com.financeapp.core.transaction.TransactionType.EXPENSE,
                com.financeapp.core.transaction.TransactionStatus.COMPLETED, null, null, List.of(), java.util.Set.of()));
        byte[] pdf = "%PDF-1.4\nJUSTIFICATIF-SECRET\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var saved = db.attachments.attach(t.id(), "facture.pdf", pdf);

        Path export = backups.exportTo(dir.resolve("avec-justificatif.db")).file();
        assertFalse(new String(Files.readAllBytes(export), "ISO-8859-1").contains("JUSTIFICATIF-SECRET"),
                "chiffre dans la sauvegarde");
        db.attachments.delete(saved.id());

        backups.scheduleRestore(export, null);
        byte[] dek = db.key.copy();
        db.key.lock();
        assertTrue(BackupService.applyPendingRestore(db.dirs, tickingClock));
        SqliteTestDb reopened = new SqliteTestDb(db.dirs.root(), TODAY, dek);
        assertArrayEquals(pdf, reopened.attachments.content(saved.id()));
    }
}
