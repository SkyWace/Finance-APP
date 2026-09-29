package com.financeapp.infra.security;

import org.sqlite.SQLiteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Chiffrement d'une base SQLite existante en clair (donnees de la V1).
 *
 * <p>Procede sans jamais toucher a l'original tant que la copie chiffree
 * n'est pas verifiee : copie coherente ({@code VACUUM INTO}, contenu du WAL
 * inclus) → passage en journal DELETE (le rechiffrement est refuse en WAL) →
 * {@code PRAGMA rekey} → verification (integrite + nombre d'objets) →
 * remplacement atomique de l'original.
 *
 * <p>Limite : les octets en clair de l'ancien fichier peuvent subsister sur
 * le disque (aucun effacement sur n'est garanti, notamment sur SSD).
 */
public final class DatabaseEncryption {

    private static final Logger log = LoggerFactory.getLogger(DatabaseEncryption.class);
    private static final byte[] SQLITE_HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private DatabaseEncryption() {
    }

    /**
     * Verifie que le pilote SQLite charge sait chiffrer (SQLite3MultipleCiphers).
     * Un pilote SQLite standard accepte {@code PRAGMA rekey} sans rien faire :
     * sans ce controle, les donnees resteraient en clair sans erreur visible.
     *
     * @throws IllegalStateException si le chiffrement n'est pas disponible
     */
    public static String requireCipherSupport() {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT sqlite3mc_version()")) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Le pilote SQLite charge ne prend pas en charge le chiffrement "
                    + "(SQLite3MultipleCiphers introuvable). Verifiez qu'aucun ancien sqlite-jdbc n'est present dans lib/.", e);
        }
    }

    /** Vrai si le fichier est une base SQLite NON chiffree (en-tete standard lisible). */
    public static boolean isPlaintextSqlite(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < SQLITE_HEADER.length) {
            return false;
        }
        byte[] head = new byte[SQLITE_HEADER.length];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(head, 0, head.length) != head.length) {
                return false;
            }
        }
        return Arrays.equals(head, SQLITE_HEADER);
    }

    public static void encryptInPlace(Path database, byte[] rawKey) throws IOException, SQLException {
        Path work = database.resolveSibling(database.getFileName() + ".encrypting");
        Files.deleteIfExists(work);
        long expectedObjects;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            try (Statement s = c.createStatement()) {
                expectedObjects = count(s, "SELECT count(*) FROM sqlite_master");
            }
            // Instruction dediee : ce pilote refuse VACUUM sur un Statement deja utilise.
            try (Statement s = c.createStatement()) {
                s.execute("VACUUM INTO '" + work.toAbsolutePath().toString().replace("'", "''") + "'");
            }
        }
        try {
            SQLiteConfig plain = new SQLiteConfig();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + work.toAbsolutePath(), plain.toProperties())) {
                exec(c, "PRAGMA journal_mode = DELETE");
            }
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + work.toAbsolutePath(), plain.toProperties())) {
                exec(c, "PRAGMA cipher = 'sqlcipher'");
                exec(c, "PRAGMA legacy = 4");
                exec(c, "PRAGMA rekey = \"x'" + hex(rawKey) + "'\"");
            }
            if (isPlaintextSqlite(work)) {
                throw new SQLException("Le chiffrement n'a pas ete applique");
            }
            try (Connection c = EncryptedDataSource.open(work, rawKey, true); Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
                    if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                        throw new SQLException("Controle d'integrite en echec apres chiffrement");
                    }
                }
                if (count(s, "SELECT count(*) FROM sqlite_master") != expectedObjects) {
                    throw new SQLException("Contenu different apres chiffrement");
                }
            }
            Files.move(work, database, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
            Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
            log.info("Base chiffree : {}", database.getFileName());
        } finally {
            Files.deleteIfExists(work);
            Files.deleteIfExists(work.resolveSibling(work.getFileName() + "-journal"));
        }
    }

    /** Execute une instruction sur un Statement neuf, en lisant son eventuel resultat. */
    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            if (s.execute(sql)) {
                try (ResultSet rs = s.getResultSet()) {
                    while (rs.next()) {
                        // consommation volontaire
                    }
                }
            }
        }
    }

    private static long count(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString().toUpperCase();
    }
}
