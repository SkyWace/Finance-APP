package com.financeapp.desktop;

import com.financeapp.desktop.security.SecuritySession;
import com.financeapp.infra.security.DatabaseEncryption;
import com.financeapp.infra.security.DatabaseKey;
import com.financeapp.infra.storage.ProfileRegistry;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;


/**
 * Cycle de vie JavaFX. Aucune donnee n'est accessible avant la saisie du mot
 * de passe maitre : {@link #init()} ne lit que la liste des utilisateurs ; une
 * restauration en attente est appliquee au choix de l'utilisateur (fichiers
 * chiffres deplaces tels quels), la base n'est ouverte
 * qu'apres deverrouillage ({@link SecuritySession}). Une sauvegarde
 * automatique est faite a la fermeture si l'application est deverrouillee.
 */
public class FinanceFxApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(FinanceFxApplication.class);

    private final DatabaseKey databaseKey = new DatabaseKey();
    private ProfileRegistry registry;
    private SecuritySession session;
    private Throwable startupError;

    @Override
    public void init() {
        try {
            registry = new ProfileRegistry(Bootstrap.resolveRoot());
            DatabaseEncryption.requireCipherSupport();
            registry.list(); // une installation anterieure devient le premier profil
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
        Scene scene = new Scene(new StackPane(), 1320, 840);
        com.financeapp.desktop.ui.common.Theme.install(scene);
        stage.setTitle(appName);
        stage.setMinWidth(1024);
        stage.setMinHeight(680);
        stage.setScene(scene);
        session = new SecuritySession(registry, databaseKey, stage, scene, appName,
                getParameters().getRaw().toArray(String[]::new));
        session.start();
        stage.show();
    }

    @Override
    public void stop() {
        ConfigurableApplicationContext context = session == null ? null : session.context();
        try {
            if (session != null) {
                session.backupBeforeClosing();
            }
        } finally {
            databaseKey.lock();
            if (context != null) {
                context.close();
            }
            if (session != null) {
                session.shutdown();
            }
        }
    }
}
