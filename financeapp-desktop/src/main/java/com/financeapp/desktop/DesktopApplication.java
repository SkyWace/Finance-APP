package com.financeapp.desktop;

import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.storage.AppDirectories;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * Contexte Spring de l'application. Pas d'auto-configuration : la base
 * SQLite, Flyway et les services sont cables explicitement dans
 * {@link AppConfiguration} (ordre de demarrage maitrise, demarrage plus rapide).
 */
@SpringBootConfiguration
@Import(AppConfiguration.class)
public class DesktopApplication {

    /** @param key cle de la base, deja deverrouillee (le contexte ouvre et migre la base au demarrage) */
    public static ConfigurableApplicationContext start(AppDirectories directories, DatabaseKey key, String... args) {
        return new SpringApplicationBuilder(DesktopApplication.class)
                .bannerMode(Banner.Mode.OFF)
                .headless(false)
                .properties("logging.file.name=" + directories.logsDir().resolve("financeapp.log"))
                .initializers(ctx -> {
                    ctx.getBeanFactory().registerSingleton("appDirectories", directories);
                    ctx.getBeanFactory().registerSingleton("databaseKey", key);
                })
                .run(args);
    }
}
