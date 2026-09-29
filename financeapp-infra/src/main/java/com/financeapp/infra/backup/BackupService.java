package com.financeapp.infra.backup;

import com.financeapp.infra.security.DatabaseEncryption;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.security.DatabaseLockedException;
import com.financeapp.infra.security.EncryptedDataSource;
import com.financeapp.infra.security.InvalidSecretException;
import com.financeapp.infra.security.Keystore;
import com.financeapp.infra.security.VaultService;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Sauvegardes locales de la base chiffree.
 *
 * <p>Une sauvegarde = deux fichiers :
 * <ul>
 *   <li>{@code nom.db} : copie coherente a chaud ({@code VACUUM INTO}), chiffree
 *       avec la meme cle que la base ;</li>
 *   <li>{@code nom.db.key} : copie du trousseau (aucun secret en clair) qui permet
 *       de la restaurer ailleurs avec le mot de passe en vigueur au moment de la sauvegarde.</li>
 * </ul>
 *
 * <p>Garanties : ecriture dans un fichier temporaire verifie ({@code PRAGMA
 * integrity_check} + presence du schema) puis renomme ; sauvegardes
 * automatiques horodatees, jamais ecrasees, rotation apres verification de la
 * nouvelle ; restauration differee au prochain demarrage, precedee d'une
 * copie de securite brute (fichiers chiffres tels quels) de l'etat remplace.
 */
public final class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    static final String AUTO_PREFIX = "financeapp-auto-";
    static final String SAFETY_PREFIX = "financeapp-avant-restauration-";
    static final String EXTENSION = ".db";
    static final String KEY_SUFFIX = ".key";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Ce qu'il faut pour restaurer un fichier donne. */
    public enum RestoreRequirement {
        /** Chiffree avec la cle de cette installation (ou sauvegarde V1 en clair) : aucun mot de passe. */
        NONE,
        /** Autre cle : le mot de passe associe a son fichier .key est requis. */
        BACKUP_PASSWORD
    }

    private final DataSource dataSource;
    private final AppDirectories directories;
    private final DatabaseKey key;
    private final String latestKnownSchemaVersion;
    private final Clock clock;

    public BackupService(DataSource dataSource, AppDirectories directories, DatabaseKey key,
                         String latestKnownSchemaVersion, Clock clock) {
        this.dataSource = dataSource;
        this.directories = directories;
        this.key = key;
        this.latestKnownSchemaVersion = latestKnownSchemaVersion;
        this.clock = clock;
    }

    /** Sauvegarde manuelle vers un fichier choisi par l'utilisateur (+ son fichier .key). */
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
                boolean automatic = f.getFileName().toString().startsWith(AUTO_PREFIX);
                try {
                    result.add(inspectWithCurrentKey(f, automatic));
                } catch (InvalidBackupException e) {
                    if (Files.exists(keyFileOf(f))) {
                        result.add(new BackupInfo(f, Files.getLastModifiedTime(f).toInstant(), Files.size(f), null,
                                -1, -1, automatic, false));
                    } else {
                        log.warn("Fichier de sauvegarde ignore (illisible) : {}", f.getFileName());
                    }
                }
            }
        }
        result.sort(Comparator.comparing(BackupInfo::modifiedAt).reversed());
        return result;
    }

    public RestoreRequirement restoreRequirement(Path source) throws InvalidBackupException {
        if (!Files.isRegularFile(source)) {
            throw new InvalidBackupException("Fichier introuvable : " + source.getFileName());
        }
        try {
            if (DatabaseEncryption.isPlaintextSqlite(source) || opensWithCurrentKey(source)) {
                return RestoreRequirement.NONE;
            }
        } catch (IOException e) {
            throw new InvalidBackupException("Fichier illisible", e);
        }
        if (Files.exists(keyFileOf(source))) {
            return RestoreRequirement.BACKUP_PASSWORD;
        }
        throw new InvalidBackupException("Fichier illisible : ce n'est pas une sauvegarde de l'application, "
                + "ou elle a été chiffrée avec une autre clé et son fichier « .key » est introuvable à côté d'elle.");
    }

    /** La cle actuelle dechiffre-t-elle ce fichier ? (sans juger de son contenu) */
    private boolean opensWithCurrentKey(Path file) {
        byte[] raw;
        try {
            raw = key.copy();
        } catch (DatabaseLockedException e) {
            return false;
        }
        try (Connection c = EncryptedDataSource.open(file, raw, true)) {
            single(c, "SELECT count(*) FROM sqlite_master");
            return true;
        } catch (SQLException e) {
            return false;
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /**
     * Verifie un fichier et le place en attente : il remplacera la base au
     * prochain demarrage (voir {@link #applyPendingRestore}).
     *
     * @param backupPassword mot de passe associe au fichier .key de la sauvegarde,
     *                       requis seulement si elle a ete chiffree avec une autre cle
     */
    public BackupInfo scheduleRestore(Path source, char[] backupPassword) throws IOException, InvalidBackupException {
        BackupInfo info;
        Path keystoreToInstall = null;
        if (restoreRequirement(source) == RestoreRequirement.NONE) {
            info = inspectWithCurrentKey(source, false);
        } else {
            if (backupPassword == null || backupPassword.length == 0) {
                throw new InvalidBackupException("Cette sauvegarde a été chiffrée avec une autre clé : "
                        + "saisissez le mot de passe maître en vigueur lors de sa création.");
            }
            Keystore ks = Keystore.load(keyFileOf(source));
            byte[] dek;
            try {
                dek = VaultService.unwrapWithPassword(ks, backupPassword);
            } catch (InvalidSecretException e) {
                throw new InvalidBackupException("Mot de passe de la sauvegarde incorrect");
            }
            try {
                info = inspect(source, dek, false);
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
            keystoreToInstall = keyFileOf(source);
        }
        Path pending = directories.pendingRestoreFile();
        Path temp = pending.resolveSibling(pending.getFileName() + ".tmp");
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(temp, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (keystoreToInstall != null) {
            Files.copy(keystoreToInstall, directories.pendingRestoreKeystore(), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(directories.pendingRestoreKeystore());
        }
        log.info("Restauration programmee pour le prochain demarrage");
        return info;
    }

    public boolean hasPendingRestore() {
        return Files.exists(directories.pendingRestoreFile());
    }

    public void cancelPendingRestore() throws IOException {
        Files.deleteIfExists(directories.pendingRestoreFile());
        Files.deleteIfExists(directories.pendingRestoreKeystore());
    }

    /**
     * A appeler au demarrage, AVANT toute ouverture de la base et avant la
     * saisie du mot de passe. Copie de securite brute de l'etat actuel (base,
     * journaux WAL/SHM et trousseau, dans un sous-dossier dedie : aucune cle
     * n'est necessaire), puis remplacement. Les anciens fichiers WAL/SHM sont
     * supprimes : rejoues sur la base restauree, ils la corrompraient.
     *
     * @return {@code true} si une restauration a ete appliquee
     */
    public static boolean applyPendingRestore(AppDirectories directories, Clock clock) throws IOException {
        Path pending = directories.pendingRestoreFile();
        if (!Files.exists(pending)) {
            return false;
        }
        Path db = directories.databaseFile();
        Path safety = directories.backupsDir().resolve(SAFETY_PREFIX + LocalDateTime.now(clock).format(STAMP));
        Files.createDirectories(safety);
        for (Path f : List.of(db, sibling(db, "-wal"), sibling(db, "-shm"), directories.keystoreFile())) {
            if (Files.exists(f)) {
                Files.copy(f, safety.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        log.info("Copie de securite avant restauration : {}", safety.getFileName());
        Files.deleteIfExists(sibling(db, "-wal"));
        Files.deleteIfExists(sibling(db, "-shm"));
        Files.move(pending, db, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(directories.pendingRestoreKeystore())) {
            Files.move(directories.pendingRestoreKeystore(), directories.keystoreFile(), StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Sauvegarde restauree");
        return true;
    }

    private void snapshotTo(Path temp) throws IOException {
        Files.deleteIfExists(temp);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // VACUUM INTO produit une copie chiffree avec la meme cle que la base.
            s.execute("VACUUM INTO '" + sqlLiteral(temp) + "'");
        } catch (DatabaseLockedException e) {
            throw new IOException("Application verrouillee : sauvegarde impossible", e);
        } catch (SQLException e) {
            Files.deleteIfExists(temp);
            throw new IOException("La copie de la base a echoue", e);
        }
    }

    private BackupInfo verifyAndPublish(Path temp, Path target, boolean automatic) throws IOException, InvalidBackupException {
        try {
            inspectWithCurrentKey(temp, automatic);
            if (DatabaseEncryption.isPlaintextSqlite(temp)) {
                throw new InvalidBackupException("La copie n'est pas chiffrée");
            }
        } catch (InvalidBackupException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (Files.exists(directories.keystoreFile())) {
            Files.copy(directories.keystoreFile(), keyFileOf(target), StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Sauvegarde creee : {}", target.getFileName());
        return inspectWithCurrentKey(target, automatic);
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
            Files.deleteIfExists(keyFileOf(old));
            log.info("Rotation : ancienne sauvegarde supprimee ({})", old.getFileName());
        }
    }

    private BackupInfo inspectWithCurrentKey(Path file, boolean automatic) throws InvalidBackupException {
        try {
            if (DatabaseEncryption.isPlaintextSqlite(file)) {
                return inspect(file, null, automatic); // sauvegarde de la V1, chiffree apres restauration
            }
        } catch (IOException e) {
            throw new InvalidBackupException("Fichier illisible", e);
        }
        byte[] raw;
        try {
            raw = key.copy();
        } catch (DatabaseLockedException e) {
            throw new InvalidBackupException("Application verrouillée", e);
        }
        try {
            return inspect(file, raw, automatic);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /** Ouvre le fichier en lecture seule et verifie qu'il s'agit d'une base de l'application saine et compatible. */
    BackupInfo inspect(Path file, byte[] rawKey, boolean automatic) throws InvalidBackupException {
        if (!Files.isRegularFile(file)) {
            throw new InvalidBackupException("Fichier introuvable : " + file.getFileName());
        }
        try (Connection c = rawKey != null ? EncryptedDataSource.open(file, rawKey, true) : openPlainReadOnly(file)) {
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
                    accounts, transactions, automatic, true);
        } catch (SQLException | IOException e) {
            throw new InvalidBackupException("Fichier illisible avec la clé actuelle, ou qui n'est pas une base de l'application", e);
        }
    }

    private static Connection openPlainReadOnly(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties());
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

    static Path keyFileOf(Path backup) {
        return backup.resolveSibling(backup.getFileName() + KEY_SUFFIX);
    }

    private static Path sibling(Path db, String suffix) {
        return db.resolveSibling(db.getFileName() + suffix);
    }

    private static String sqlLiteral(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }
}
