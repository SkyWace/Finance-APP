package com.financeapp.desktop;

import com.financeapp.core.settings.SettingsService;
import com.financeapp.desktop.ui.MainWindow;
import com.financeapp.desktop.ui.common.AppServices;
import com.financeapp.infra.backup.BackupService;
import com.financeapp.infra.storage.AppDirectories;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Objects;

/**
 * Cycle de vie JavaFX : le contexte Spring (base, migrations, services) est
 * demarre dans {@link #init()} hors du thread graphique, la fenetre dans
 * {@link #start(Stage)}, et une sauvegarde automatique est faite a la fermeture.
 */
public class FinanceFxApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(FinanceFxApplication.class);

    private ConfigurableApplicationContext context;
    private Throwable startupError;

    @Override
    public void init() {
        try {
            AppDirectories directories = Bootstrap.resolveDirectories();
            context = DesktopApplication.start(directories, getParameters().getRaw().toArray(String[]::new));
        } catch (Throwable e) {
            startupError = e;
        }
    }

    @Override
    public void start(Stage stage) {
        if (startupError != null) {
            log.error("Echec du demarrage", startupError);
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "L'application n'a pas pu démarrer :\n" + rootMessage(startupError)
                            + "\n\nConsultez le fichier de log pour plus de détails.");
            alert.setHeaderText("Démarrage impossible");
            alert.showAndWait();
            Platform.exit();
            return;
        }
        AppServices services = AppServices.from(context);
        MainWindow window = new MainWindow(services, stage);
        Scene scene = new Scene(window.root(), 1320, 840);
        scene.getStylesheets().add(Objects.requireNonNull(
                FinanceFxApplication.class.getResource("theme.css"), "theme.css introuvable").toExternalForm());
        window.installShortcuts(scene);
        stage.setTitle(services.properties().name());
        stage.setMinWidth(1024);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.show();
        log.info("{} {} demarre", services.properties().name(), services.properties().version());
    }

    @Override
    public void stop() {
        if (context == null) {
            return;
        }
        try {
            SettingsService settings = context.getBean(SettingsService.class);
            BackupService backups = context.getBean(BackupService.class);
            if (settings.autoBackupEnabled() && !backups.hasPendingRestore()) {
                backups.createAutomaticBackup(settings.autoBackupKeep());
            }
        } catch (Exception e) {
            log.error("La sauvegarde automatique de fermeture a echoue", e);
        } finally {
            context.close();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
