package com.financeapp.desktop;

import com.financeapp.core.settings.SettingsService;
import com.financeapp.desktop.security.SecuritySession;
import com.financeapp.infra.backup.BackupService;
import com.financeapp.infra.security.Argon2Params;
import com.financeapp.infra.security.DatabaseEncryption;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.security.VaultService;
import com.financeapp.infra.storage.AppDirectories;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Clock;
import java.util.Objects;

/**
 * Cycle de vie JavaFX. Aucune donnee n'est accessible avant la saisie du mot
 * de passe maitre : {@link #init()} ne fait qu'appliquer une restauration en
 * attente (fichiers chiffres deplaces tels quels), la base n'est ouverte
 * qu'apres deverrouillage ({@link SecuritySession}). Une sauvegarde
 * automatique est faite a la fermeture si l'application est deverrouillee.
 */
public class FinanceFxApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(FinanceFxApplication.class);

    private final DatabaseKey databaseKey = new DatabaseKey();
    private AppDirectories directories;
    private SecuritySession session;
    private Throwable startupError;

    @Override
    public void init() {
        try {
            directories = Bootstrap.resolveDirectories();
            DatabaseEncryption.requireCipherSupport();
            BackupService.applyPendingRestore(directories, Clock.systemDefaultZone());
        } catch (Throwable e) {
            startupError = e;
        }
    }

    @Override
    public void start(Stage stage) {
        com.financeapp.desktop.ui.common.Browser.init(getHostServices());
        if (startupError != null) {
            log.error("Echec du demarrage", startupError);
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "L'application n'a pas pu démarrer :\n" + startupError.getMessage()
                            + "\n\nConsultez le fichier de log pour plus de détails.");
            alert.setHeaderText("Démarrage impossible");
            alert.showAndWait();
            Platform.exit();
            return;
        }
        String appName = Bootstrap.appName();
        VaultService vault = new VaultService(directories.keystoreFile(), directories.databaseFile(),
                directories.backupsDir(), Argon2Params.DEFAULT);
        Scene scene = new Scene(new StackPane(), 1320, 840);
        scene.getStylesheets().add(Objects.requireNonNull(
                FinanceFxApplication.class.getResource("theme.css"), "theme.css introuvable").toExternalForm());
        stage.setTitle(appName);
        stage.setMinWidth(1024);
        stage.setMinHeight(680);
        stage.setScene(scene);
        session = new SecuritySession(directories, vault, databaseKey, stage, scene, appName,
                getParameters().getRaw().toArray(String[]::new));
        session.start();
        stage.show();
    }

    @Override
    public void stop() {
        ConfigurableApplicationContext context = session == null ? null : session.context();
        try {
            if (context != null && databaseKey.isUnlocked()) {
                SettingsService settings = context.getBean(SettingsService.class);
                BackupService backups = context.getBean(BackupService.class);
                if (settings.autoBackupEnabled() && !backups.hasPendingRestore()) {
                    backups.createAutomaticBackup(settings.autoBackupKeep());
                }
            }
        } catch (Exception e) {
            log.error("La sauvegarde automatique de fermeture a echoue", e);
        } finally {
            databaseKey.lock();
            if (context != null) {
                context.close();
            }
        }
    }
}
