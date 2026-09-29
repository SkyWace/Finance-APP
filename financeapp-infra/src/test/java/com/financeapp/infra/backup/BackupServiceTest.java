package com.financeapp.infra.backup;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.infra.db.SqliteDataSourceFactory;
import com.financeapp.infra.db.SqliteTestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    @TempDir
    Path dir;
    private SqliteTestDb db;
    private final AtomicLong seconds = new AtomicLong(Instant.parse("2026-09-29T10:00:00Z").getEpochSecond());
    private BackupService backups;

    /** Horloge qui avance d'une seconde a chaque lecture : noms de fichiers distincts et ordonnes. */
    private final Clock tickingClock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochSecond(seconds.getAndIncrement()); }
    };

    @BeforeEach
    void setUp() {
        db = new SqliteTestDb(dir, TODAY);
        db.accounts.save(Account.create("Compte courant", AccountType.CHECKING, Money.eur("100"), TODAY));
        backups = new BackupService(db.dataSource, db.dirs, db.migrator.latestKnownVersion(), tickingClock);
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
        assertEquals(1, all.getFirst().accounts());
        assertTrue(all.stream().filter(BackupInfo::automatic)
                .allMatch(b -> b.file().getFileName().toString().compareTo("financeapp-auto-20260929-100002") > 0),
                "ce sont les plus anciennes qui ont ete supprimees");
        try (var files = Files.list(db.dirs.backupsDir())) {
            assertTrue(files.noneMatch(f -> f.toString().endsWith(".tmp")), "aucun fichier temporaire residuel");
        }
    }

    @Test
    void invalidFilesAreRejected() throws Exception {
        Path garbage = dir.resolve("pas-une-base.db");
        Files.writeString(garbage, "ceci n'est pas une base SQLite");
        assertThrows(InvalidBackupException.class, () -> backups.scheduleRestore(garbage));

        Path otherDb = dir.resolve("autre.db");
        try (var c = SqliteDataSourceFactory.create(otherDb).getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE foo (id INTEGER)");
        }
        assertThrows(InvalidBackupException.class, () -> backups.scheduleRestore(otherDb));
        assertFalse(backups.hasPendingRestore());
    }

    @Test
    void backupFromANewerSchemaIsRejected() throws Exception {
        Path export = backups.exportTo(dir.resolve("export.db")).file();
        BackupService olderApp = new BackupService(db.dataSource, db.dirs, "1", tickingClock);
        InvalidBackupException e = assertThrows(InvalidBackupException.class, () -> olderApp.scheduleRestore(export));
        assertTrue(e.getMessage().contains("plus récente"));
    }

    @Test
    void restoreIsAppliedAtNextStartupWithASafetyCopy() throws Exception {
        Path export = backups.exportTo(dir.resolve("export.db")).file();
        // Modification posterieure a la sauvegarde
        db.accounts.save(Account.create("Livret", AccountType.PASSBOOK, Money.eur("50"), TODAY));
        assertEquals(2, db.accounts.findAll().size());

        backups.scheduleRestore(export);
        assertTrue(backups.hasPendingRestore());

        // "Redemarrage" : application avant ouverture de la base
        assertTrue(BackupService.applyPendingRestore(db.dirs, tickingClock));
        assertFalse(backups.hasPendingRestore());

        SqliteTestDb reopened = new SqliteTestDb(dir, TODAY);
        assertEquals(1, reopened.accounts.findAll().size());
        try (var files = Files.list(db.dirs.backupsDir())) {
            Path safety = files.filter(f -> f.getFileName().toString().startsWith(BackupService.SAFETY_PREFIX))
                    .findFirst().orElseThrow();
            assertEquals(2, backups.inspect(safety, false).accounts(), "la base remplacee reste recuperable");
        }
        assertFalse(BackupService.applyPendingRestore(db.dirs, tickingClock));
    }

    @Test
    void versionComparison() {
        assertTrue(BackupService.compareVersions("10", "9") > 0);
        assertEquals(0, BackupService.compareVersions("2", "2.0"));
        assertTrue(BackupService.compareVersions("1.1", "2") < 0);
    }
}
