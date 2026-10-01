package com.financeapp.desktop.ui.security;

import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.infra.security.InvalidSecretException;
import com.financeapp.infra.security.VaultService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Ecran affiche avant l'acces aux donnees : creation du mot de passe maitre
 * (premier lancement ou chiffrement des donnees de la V1), deverrouillage,
 * recuperation par la cle de secours, ou import d'un trousseau perdu.
 *
 * <p>Les derivations Argon2id (~0,3 s chacune) s'executent hors du thread
 * JavaFX. Apres plusieurs echecs, un delai croissant s'ajoute entre deux essais.
 */
public final class LockScreen {

    private static final Logger log = LoggerFactory.getLogger(LockScreen.class);

    private final VaultService vault;
    private final String appName;
    private final Consumer<byte[]> onUnlocked;
    private final Window window;
    private final StackPane root = new StackPane();
    private final String userName;
    private final Runnable onSwitchUser;
    private int failures;

    /** @param onUnlocked recoit la cle de la base (a effacer apres usage), sur le thread JavaFX */
    public LockScreen(VaultService vault, String appName, Window window, Consumer<byte[]> onUnlocked) {
        this(vault, appName, null, null, window, onUnlocked);
    }

    /**
     * @param userName     utilisateur dont les donnees sont ouvertes ({@code null} : non affiche)
     * @param onSwitchUser retour au choix de l'utilisateur ({@code null} : pas de lien)
     */
    public LockScreen(VaultService vault, String appName, String userName, Runnable onSwitchUser, Window window,
                      Consumer<byte[]> onUnlocked) {
        this.vault = vault;
        this.appName = appName;
        this.userName = userName;
        this.onSwitchUser = onSwitchUser;
        this.window = window;
        this.onUnlocked = onUnlocked;
        root.getStyleClass().add("lock-root");
    }

    public Parent root() {
        return root;
    }

    /** Choisit l'ecran selon l'etat du trousseau. */
    public LockScreen showForCurrentState() {
        try {
            switch (vault.status()) {
                case NEW -> showCreate(false);
                case PLAINTEXT_DATA -> showCreate(true);
                case LOCKED -> showUnlock();
                case KEYSTORE_MISSING -> showKeystoreMissing();
            }
        } catch (Exception e) {
            log.error("Etat du trousseau illisible", e);
            show(card(Widgets.label("Impossible de lire le trousseau de clés.", "lock-title"),
                    text("Consultez le fichier de log pour plus de détails.")));
        }
        return this;
    }

    public void showMessage(String title, String message) {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(42, 42);
        show(withSwitch(card(Widgets.label(title, "lock-title"), text(message), spinner)));
    }

    // ---------------------------------------------------------------- creation

    private void showCreate(boolean existingData) {
        PasswordField password = new PasswordField();
        PasswordField confirm = new PasswordField();
        password.setPromptText("Mot de passe maître (" + VaultService.MIN_PASSWORD_LENGTH + " caractères minimum)");
        confirm.setPromptText("Confirmation");
        Label strength = Widgets.label("", "strength");
        password.textProperty().addListener((o, old, v) -> {
            strength.setText(v.isEmpty() ? "" : "Robustesse : " + PasswordStrength.label(v));
            strength.getStyleClass().removeAll("strength-weak", "strength-fair", "strength-good");
            strength.getStyleClass().add(PasswordStrength.styleClass(v));
        });
        Label error = Widgets.label("", "form-error");
        error.setWrapText(true);
        Button create = new Button(existingData ? "Créer le mot de passe et chiffrer mes données" : "Créer le mot de passe");
        create.getStyleClass().add("primary");
        create.setDefaultButton(true);
        create.setMaxWidth(Double.MAX_VALUE);

        Label intro = text(existingData
                ? "Vos données existantes vont être chiffrées sur ce disque. Elles ne seront ensuite lisibles "
                  + "qu'avec votre mot de passe maître (ou votre clé de récupération)."
                : "Vos données financières seront chiffrées sur ce disque. Elles ne seront lisibles qu'avec "
                  + "ce mot de passe (ou la clé de récupération qui vous sera remise juste après).");
        Label warning = text("Aucun serveur ne conserve ce mot de passe : personne ne pourra le réinitialiser pour vous. "
                + "Une phrase de plusieurs mots est à la fois solide et facile à retenir.");
        warning.getStyleClass().add("lock-warning");

        create.setOnAction(e -> {
            error.setText("");
            if (!password.getText().equals(confirm.getText())) {
                error.setText("Les deux saisies ne correspondent pas.");
                return;
            }
            char[] secret = password.getText().toCharArray();
            try {
                VaultService.checkPasswordPolicy(secret);
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                Arrays.fill(secret, '\0');
                return;
            }
            password.clear();
            confirm.clear();
            showMessage(existingData ? "Chiffrement de vos données…" : "Création du coffre…",
                    "Cela ne prend que quelques secondes.");
            background(() -> vault.create(secret), created -> {
                Arrays.fill(secret, '\0');
                show(RecoveryKeyPanel.build(created.recoveryKey(), "Accéder à mes finances", () -> {
                    Arrays.fill(created.recoveryKey(), '\0');
                    onUnlocked.accept(created.dek());
                }));
            }, ex -> {
                Arrays.fill(secret, '\0');
                log.error("Creation du mot de passe maitre impossible", ex);
                showCreate(existingData);
                showError("La création a échoué : " + ex.getMessage());
            });
        });
        show(withSwitch(card(brand(), Widgets.label("Créez votre mot de passe maître", "lock-title"), intro,
                password, strength, confirm, warning, error, create)));
        Platform.runLater(password::requestFocus);
    }

    // --------------------------------------------------------- deverrouillage

    private void showUnlock() {
        PasswordField password = new PasswordField();
        password.setPromptText("Mot de passe maître");
        Label error = Widgets.label("", "form-error");
        error.setWrapText(true);
        Button unlock = new Button("Déverrouiller");
        unlock.getStyleClass().add("primary");
        unlock.setDefaultButton(true);
        unlock.setMaxWidth(Double.MAX_VALUE);
        Button forgot = link("Mot de passe oublié ? Utiliser la clé de récupération", this::showRecover);
        ProgressIndicator busy = new ProgressIndicator();
        busy.setMaxSize(28, 28);
        busy.setVisible(false);

        unlock.setOnAction(e -> {
            if (password.getText().isEmpty()) {
                return;
            }
            char[] secret = password.getText().toCharArray();
            password.clear();
            error.setText("");
            setBusy(true, unlock, password, busy);
            background(() -> vault.unlock(secret), dek -> {
                Arrays.fill(secret, '\0');
                failures = 0;
                onUnlocked.accept(dek);
            }, ex -> {
                Arrays.fill(secret, '\0');
                setBusy(false, unlock, password, busy);
                if (ex instanceof InvalidSecretException) {
                    failures++;
                    error.setText(ex.getMessage() + (failures >= 3 ? " (" + failures + " essais)" : ""));
                    throttle(unlock, password);
                } else {
                    log.error("Deverrouillage impossible", ex);
                    error.setText("Ouverture impossible : " + ex.getMessage());
                }
                password.requestFocus();
            });
        });
        show(withSwitch(card(brand(), Widgets.label("Application verrouillée", "lock-title"),
                text("Saisissez votre mot de passe maître pour accéder à vos données."),
                password, error, unlock, busy, forgot)));
        Platform.runLater(password::requestFocus);
    }

    /** Apres 3 echecs, delai croissant (2, 4, 8... jusqu'a 30 s) avant un nouvel essai. */
    private void throttle(Button button, PasswordField field) {
        if (failures < 3) {
            return;
        }
        int seconds = (int) Math.min(30, Math.pow(2, failures - 2));
        button.setDisable(true);
        field.setDisable(true);
        PauseTransition wait = new PauseTransition(Duration.seconds(seconds));
        wait.setOnFinished(e -> {
            button.setDisable(false);
            field.setDisable(false);
            field.requestFocus();
        });
        wait.play();
    }

    // ------------------------------------------------------------ recuperation

    private void showRecover() {
        TextField recovery = new TextField();
        recovery.setPromptText("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX");
        recovery.getStyleClass().add("recovery-input");
        PasswordField password = new PasswordField();
        PasswordField confirm = new PasswordField();
        password.setPromptText("Nouveau mot de passe maître");
        confirm.setPromptText("Confirmation");
        Label error = Widgets.label("", "form-error");
        error.setWrapText(true);
        Button apply = new Button("Définir le nouveau mot de passe");
        apply.getStyleClass().add("primary");
        apply.setDefaultButton(true);
        apply.setMaxWidth(Double.MAX_VALUE);
        apply.setOnAction(e -> {
            if (!password.getText().equals(confirm.getText())) {
                error.setText("Les deux saisies ne correspondent pas.");
                return;
            }
            char[] secret = password.getText().toCharArray();
            String key = recovery.getText();
            password.clear();
            confirm.clear();
            error.setText("");
            apply.setDisable(true);
            background(() -> vault.recover(key, secret), dek -> {
                Arrays.fill(secret, '\0');
                onUnlocked.accept(dek);
            }, ex -> {
                Arrays.fill(secret, '\0');
                apply.setDisable(false);
                error.setText(ex instanceof InvalidSecretException || ex instanceof IllegalArgumentException
                        ? ex.getMessage() : "Opération impossible : " + ex.getMessage());
            });
        });
        show(card(brand(), Widgets.label("Récupération de l'accès", "lock-title"),
                text("Saisissez la clé de récupération remise lors de la création du mot de passe, puis choisissez un nouveau mot de passe maître."),
                recovery, password, confirm, error, apply, link("← Retour", this::showUnlock)));
        Platform.runLater(recovery::requestFocus);
    }

    // ----------------------------------------------------- trousseau manquant

    private void showKeystoreMissing() {
        Label error = Widgets.label("", "form-error");
        error.setWrapText(true);
        Button importKey = new Button("Importer un fichier .key…");
        importKey.getStyleClass().add("primary");
        importKey.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choisir le trousseau (fichier .key accompagnant une sauvegarde)");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Trousseau (*.key, *.properties)", "*.key", "*.properties"));
            File file = chooser.showOpenDialog(window);
            if (file != null) {
                try {
                    vault.importKeystore(file.toPath());
                    showUnlock();
                } catch (Exception ex) {
                    error.setText("Fichier invalide : " + ex.getMessage());
                }
            }
        });
        show(withSwitch(card(brand(), Widgets.label("Trousseau de clés introuvable", "lock-title"),
                text("Vos données sont chiffrées, mais le fichier qui contient leur clé (keystore.properties) a disparu. "
                        + "Chaque sauvegarde est accompagnée d'une copie de ce trousseau (fichier « .key » à côté du « .db »). "
                        + "Importez-en une, puis déverrouillez avec le mot de passe en vigueur à la date de cette sauvegarde."),
                importKey, error)));
    }

    // ------------------------------------------------------------------ outils

    private interface Work<T> {
        T run() throws Exception;
    }

    private static <T> void background(Work<T> work, Consumer<T> onSuccess, Consumer<Exception> onError) {
        Thread t = new Thread(() -> {
            try {
                T result = work.run();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e));
            }
        }, "financeapp-vault");
        t.setDaemon(true);
        t.start();
    }

    private void showError(String message) {
        if (root.getChildren().getFirst() instanceof VBox card) {
            card.getChildren().stream().filter(n -> n.getStyleClass().contains("form-error")).findFirst()
                    .ifPresent(n -> ((Label) n).setText(message));
        }
    }

    private static void setBusy(boolean busy, Button button, PasswordField field, ProgressIndicator indicator) {
        button.setDisable(busy);
        field.setDisable(busy);
        indicator.setVisible(busy);
    }

    private void show(Node card) {
        root.getChildren().setAll(card);
        StackPane.setAlignment(card, Pos.CENTER);
    }

    private VBox card(Node... children) {
        VBox box = new VBox(14, children);
        box.getStyleClass().add("lock-card");
        box.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return box;
    }

    /** Nom de l'application, suivi de l'utilisateur concerne quand il y en a un. */
    private Node brand() {
        Label brand = Widgets.label(appName, "lock-brand");
        if (userName == null) {
            return brand;
        }
        return new VBox(2, brand, Widgets.label("Utilisateur : " + userName, "lock-user"));
    }

    private VBox withSwitch(VBox card) {
        if (onSwitchUser != null) {
            card.getChildren().add(link("Changer d'utilisateur", onSwitchUser));
        }
        return card;
    }

    private static Label text(String s) {
        Label l = Widgets.label(s, "lock-text");
        l.setWrapText(true);
        return l;
    }

    private static Button link(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("link");
        b.setOnAction(e -> action.run());
        return b;
    }
}
