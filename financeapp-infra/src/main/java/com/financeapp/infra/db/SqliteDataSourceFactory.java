package com.financeapp.infra.db;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

/**
 * Configure l'acces SQLite. Chaque connexion ouverte applique :
 * <ul>
 *   <li>{@code foreign_keys = ON} : integrite referentielle (desactivee par defaut dans SQLite) ;</li>
 *   <li>{@code journal_mode = WAL} : lectures concurrentes pendant une ecriture, meilleure resistance aux coupures ;</li>
 *   <li>{@code busy_timeout} : attente plutot qu'une erreur immediate si la base est verrouillee ;</li>
 *   <li>{@code synchronous = NORMAL} : compromis recommande avec WAL.</li>
 * </ul>
 * Point d'extension prevu pour le chiffrement (V1.1) : remplacer le pilote
 * par une variante compatible SQLCipher ne touche que cette classe.
 */
public final class SqliteDataSourceFactory {

    private SqliteDataSourceFactory() {
    }

    public static SQLiteDataSource create(Path databaseFile) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5_000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        return dataSource;
    }
}
