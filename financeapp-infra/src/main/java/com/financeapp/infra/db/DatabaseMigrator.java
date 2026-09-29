package com.financeapp.infra.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Arrays;

/** Applique les migrations de schema versionnees ({@code db/migration}) au demarrage. */
public final class DatabaseMigrator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    private final Flyway flyway;

    public DatabaseMigrator(DataSource dataSource) {
        this.flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }

    public void migrate() {
        MigrateResult result = flyway.migrate();
        log.info("Schema de base de donnees a jour (version {}, {} migration(s) appliquee(s))",
                result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion,
                result.migrationsExecuted);
    }

    /** Version de schema la plus recente connue de cette version de l'application. */
    public String latestKnownVersion() {
        return Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(v -> v != null)
                .max(Comparable::compareTo)
                .map(Object::toString)
                .orElse("0");
    }
}
