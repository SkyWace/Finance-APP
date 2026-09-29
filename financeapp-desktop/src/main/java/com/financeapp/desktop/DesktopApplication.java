package com.financeapp.desktop;

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

    public static ConfigurableApplicationContext start(AppDirectories directories, String... args) {
        return new SpringApplicationBuilder(DesktopApplication.class)
                .bannerMode(Banner.Mode.OFF)
                .headless(false)
                .properties("logging.file.name=" + directories.logsDir().resolve("financeapp.log"))
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("appDirectories", directories))
                .run(args);
    }
}
