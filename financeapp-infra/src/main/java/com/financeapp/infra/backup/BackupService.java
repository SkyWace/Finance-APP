package com.financeapp.infra.backup;

import com.financeapp.infra.storage.AppDirectories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sauvegardes locales de la base.
 *
 * <p>Garanties :
 * <ul>
 *   <li>copie coherente a chaud via {@code VACUUM INTO} (inclut le contenu du journal WAL) ;</li>
 *   <li>toute copie est ecrite dans un fichier temporaire, verifiee
 *       ({@code PRAGMA integrity_check} + presence du schema), puis renommee :
 *       un fichier de sauvegarde n'est jamais laisse a moitie ecrit ;</li>
 *   <li>sauvegardes automatiques horodatees : aucune ne remplace une autre ;
 *       la rotation ne supprime les plus anciennes qu'apres verification de la nouvelle ;</li>
 *   <li>restauration differee au prochain demarrage (la base n'est jamais
 *       remplacee pendant qu'elle est ouverte), precedee d'une copie de securite.</li>
 * </ul>
 */
public final class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    static final String AUTO_PREFIX = "financeapp-auto-";
    static final String SAFETY_PREFIX = "financeapp-avant-restauration-";
    static final String EXTENSION = ".db";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DataSource dataSource;
    private final AppDirectories directories;
    private final String latestKnownSchemaVersion;
    private final Clock clock;

    public BackupService(DataSource dataSource, AppDirectories directories, String latestKnownSchemaVersion, Clock clock) {
        this.dataSource = dataSource;
        this.directories = directories;
        this.latestKnownSchemaVersion = latestKnownSchemaVersion;
        this.clock = clock;
    }

    /** Sauvegarde manuelle vers un fichier choisi par l'utilisateur. */
    public BackupInfo exportTo(Path target) throws IOException, InvalidBackupException {
        Path absolute = target.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        snapshotTo(temp);
        return verifyAndPublish(temp, absolute, false);
    }

    /** Sauvegarde automatique horodatee dans le dossier des sauvegardes, puis rotation. */
    public BackupInfo createAutomaticBackup(int keep) throws IOException, InvalidBackupException {
        Files.createDirectories(directories.backupsDir());
        String stamp = LocalDateTime.now(clock).format(STAMP);
        Path target = directories.backupsDir().resolve(AUTO_PREFIX + stamp + EXTENSION);
        for (int i = 2; Files.exists(target); i++) {
            target = directories.backupsDir().resolve(AUTO_PREFIX + stamp + "-" + i + EXTENSION);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        snapshotTo(temp);
        BackupInfo info = verifyAndPublish(temp, target, true);
        rotate(keep);
        return info;
    }

    /** Sauvegardes presentes dans le dossier des sauvegardes, les plus recentes d'abord. */
    public List<BackupInfo> listBackups() throws IOException {
        List<BackupInfo> result = new ArrayList<>();
        if (!Files.isDirectory(directories.backupsDir())) {
            return result;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directories.backupsDir(), "*" + EXTENSION)) {
            for (Path f : files) {
                try {
                    result.add(inspect(f, f.getFileName().toString().startsWith(AUTO_PREFIX)));
                } catch (InvalidBackupException e) {
                    log.warn("Fichier de sauvegarde ignore (invalide) : {}", f.getFileName());
                }
            }
        }
        result.sort(Comparator.comparing(BackupInfo::modifiedAt).reversed());
        return result;
    }

    /**
     * Verifie un fichier et le place en attente : il remplacera la base au
     * prochain demarrage de l'application (voir {@link #applyPendingRestore}).
     */
    public BackupInfo scheduleRestore(Path source) throws IOException, InvalidBackupException {
        BackupInfo info = inspect(source, false);
        Path pending = directories.pendingRestoreFile();
        Path temp = pending.resolveSibling(pending.getFileName() + ".tmp");
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
        inspect(temp, false);
        Files.move(temp, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Restauration programmee pour le prochain demarrage");
        return info;
    }

    public boolean hasPendingRestore() {
        return Files.exists(directories.pendingRestoreFile());
    }

    public void cancelPendingRestore() throws IOException {
        Files.deleteIfExists(directories.pendingRestoreFile());
    }

    /**
     * A appeler au demarrage, AVANT toute ouverture de la base. Si une
     * restauration est en attente : copie de securite de la base actuelle,
     * puis remplacement. Les fichiers WAL/SHM de l'ancienne base sont
     * supprimes : rejoues sur la base restauree, ils la corrompraient.
     *
     * @return {@code true} si une restauration a ete appliquee
     */
    public static boolean applyPendingRestore(AppDirectories directories, Clock clock) throws IOException, SQLException {
        Path pending = directories.pendingRestoreFile();
        if (!Files.exists(pending)) {
            return false;
        }
        Path db = directories.databaseFile();
        if (Files.exists(db)) {
            Files.createDirectories(directories.backupsDir());
            Path safety = directories.backupsDir()
                    .resolve(SAFETY_PREFIX + LocalDateTime.now(clock).format(STAMP) + EXTENSION);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 Statement s = c.createStatement()) {
                s.execute("VACUUM INTO '" + sqlLiteral(safety) + "'");
            }
            log.info("Copie de securite de la base avant restauration : {}", safety.getFileName());
        }
        Files.deleteIfExists(sibling(db, "-wal"));
        Files.deleteIfExists(sibling(db, "-shm"));
        Files.move(pending, db, StandardCopyOption.REPLACE_EXISTING);
        log.info("Sauvegarde restauree");
        return true;
    }

    private void snapshotTo(Path temp) throws IOException {
        Files.deleteIfExists(temp);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("VACUUM INTO '" + sqlLiteral(temp) + "'");
        } catch (SQLException e) {
            Files.deleteIfExists(temp);
            throw new IOException("La copie de la base a echoue", e);
        }
    }

    private BackupInfo verifyAndPublish(Path temp, Path target, boolean automatic) throws IOException, InvalidBackupException {
        try {
            inspect(temp, automatic);
        } catch (InvalidBackupException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Sauvegarde creee : {}", target.getFileName());
        return inspect(target, automatic);
    }

    /** Supprime les sauvegardes automatiques au-dela des {@code keep} plus recentes (jamais les manuelles). */
    private void rotate(int keep) throws IOException {
        List<Path> autos = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directories.backupsDir(), AUTO_PREFIX + "*" + EXTENSION)) {
            files.forEach(autos::add);
        }
        // Nom horodate yyyyMMdd-HHmmss : ordre lexical = ordre chronologique.
        autos.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for (Path old : autos.subList(Math.min(Math.max(keep, 1), autos.size()), autos.size())) {
            Files.deleteIfExists(old);
            log.info("Rotation : ancienne sauvegarde supprimee ({})", old.getFileName());
        }
    }

    /** Ouvre le fichier en lecture seule et verifie qu'il s'agit d'une base FinanceApp saine et compatible. */
    BackupInfo inspect(Path file, boolean automatic) throws InvalidBackupException {
        if (!Files.isRegularFile(file)) {
            throw new InvalidBackupException("Fichier introuvable : " + file.getFileName());
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties())) {
            String integrity = single(c, "PRAGMA integrity_check");
            if (!"ok".equalsIgnoreCase(integrity)) {
                throw new InvalidBackupException("Le fichier est endommagé (contrôle d'intégrité en échec)");
            }
            if (!"1".equals(single(c, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'flyway_schema_history'"))
                    || !"1".equals(single(c, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'transactions'"))) {
                throw new InvalidBackupException("Ce fichier n'est pas une sauvegarde de l'application");
            }
            String version = single(c, "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL "
                    + "ORDER BY installed_rank DESC LIMIT 1");
            if (version != null && compareVersions(version, latestKnownSchemaVersion) > 0) {
                throw new InvalidBackupException("Cette sauvegarde provient d'une version plus récente de l'application");
            }
            long accounts = Long.parseLong(single(c, "SELECT count(*) FROM accounts"));
            long transactions = Long.parseLong(single(c, "SELECT count(*) FROM transactions"));
            return new BackupInfo(file, Files.getLastModifiedTime(file).toInstant(), Files.size(file), version,
                    accounts, transactions, automatic);
        } catch (SQLException | IOException e) {
            throw new InvalidBackupException("Fichier illisible ou qui n'est pas une base de l'application", e);
        }
    }

    private static String single(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long va = i < pa.length ? Long.parseLong(pa[i]) : 0;
            long vb = i < pb.length ? Long.parseLong(pb[i]) : 0;
            if (va != vb) {
                return Long.compare(va, vb);
            }
        }
        return 0;
    }

    private static Path sibling(Path db, String suffix) {
        return db.resolveSibling(db.getFileName() + suffix);
    }

    private static String sqlLiteral(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }
}
