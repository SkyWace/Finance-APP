package com.financeapp.infra.security;

import org.sqlite.SQLiteConfig;
import org.sqlite.mc.SQLiteMCSqlCipherConfig;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Source de connexions a la base chiffree (SQLCipher v4, AES-256, via
 * SQLite3MultipleCiphers). La cle brute de 256 bits provient de
 * {@link DatabaseKey} : si l'application est verrouillee, aucune connexion
 * n'est accordee ({@link DatabaseLockedException}).
 *
 * <p>Pragmas appliques a chaque connexion : {@code foreign_keys = ON},
 * {@code journal_mode = WAL}, {@code synchronous = NORMAL},
 * {@code busy_timeout = 5000}.
 *
 * <p>Limite connue : le pilote JDBC transmet la cle a SQLite sous forme de
 * chaine hexadecimale (non effacable en Java) le temps d'ouvrir la connexion.
 */
public final class EncryptedDataSource implements DataSource {

    private final Path databaseFile;
    private final DatabaseKey key;

    public EncryptedDataSource(Path databaseFile, DatabaseKey key) {
        this.databaseFile = databaseFile.toAbsolutePath();
        this.key = key;
    }

    public Path databaseFile() {
        return databaseFile;
    }

    @Override
    public Connection getConnection() throws SQLException {
        byte[] raw = key.copy();
        try {
            return open(databaseFile, raw, false);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    /** Ouvre un fichier chiffre quelconque (sauvegarde a verifier, par exemple). */
    public static Connection open(Path file, byte[] rawKey, boolean readOnly) throws SQLException {
        SQLiteConfig config = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(rawKey).build();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        if (readOnly) {
            config.setReadOnly(true);
        } else {
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Pas un " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
