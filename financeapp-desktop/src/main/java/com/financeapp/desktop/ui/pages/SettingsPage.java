package com.financeapp.desktop.ui.pages;

import com.financeapp.core.available.HorizonType;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.ChangePasswordDialog;
import com.financeapp.desktop.ui.dialogs.PasswordPromptDialog;
import com.financeapp.desktop.ui.dialogs.WebExportDialog;
import com.financeapp.desktop.ui.security.RecoveryKeyPanel;
import com.financeapp.infra.backup.BackupInfo;
import com.financeapp.infra.backup.BackupService;
import com.financeapp.infra.backup.InvalidBackupException;
import com.financeapp.infra.security.InvalidSecretException;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;

/** Parametres : calcul, confidentialite, sauvegardes, informations. */
public final class SettingsPage extends Page {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Formats.LOCALE);

    public SettingsPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Paramètres";
    }

    @Override
    public void refresh() {
        content.getChildren().setAll(generalSection(), userSection(), securitySection(), privacySection(), backupSection(), updateSection(), aboutSection());
    }

    private VBox generalSection() {
        SettingsService settings = ctx.services().settings();
        ComboBox<String> currency = new ComboBox<>();
        currency.getItems().setAll("EUR", "CHF", "GBP", "USD", "CAD");
        currency.setValue(settings.baseCurrency().getCurrencyCode());
        currency.setOnAction(e -> {
            settings.setBaseCurrency(Currency.getInstance(currency.getValue()));
            ctx.events().fireChanged();
        });
        ComboBox<HorizonType> horizon = new ComboBox<>();
        horizon.getItems().setAll(HorizonType.values());
        horizon.setValue(settings.defaultHorizon());
        horizon.setOnAction(e -> settings.setDefaultHorizon(horizon.getValue()));
        CheckBox income = new CheckBox("Compter les revenus prévus certains dans le disponible réel");
        income.setSelected(settings.includeCertainIncome());
        income.setOnAction(e -> settings.setIncludeCertainIncome(income.isSelected()));
        return Widgets.section("Calculs",
                labeled("Devise de référence des totaux", currency),
                labeled("Échéance par défaut du disponible réel", horizon),
                income);
    }

    private VBox userSection() {
        javafx.scene.control.TextField name = new javafx.scene.control.TextField(ctx.security().profileName());
        name.setPrefColumnCount(20);
        Button rename = new Button("Renommer");
        rename.getStyleClass().add("secondary");
        rename.setOnAction(e -> {
            try {
                ctx.security().renameProfile(name.getText());
                ctx.events().fireChanged();
            } catch (IllegalArgumentException ex) {
                Dialogs.error(window(), ex);
            }
        });
        Button switchUser = new Button("Changer d'utilisateur");
        switchUser.getStyleClass().add("secondary");
        switchUser.setOnAction(e -> ctx.security().switchUser());
        Label how = Widgets.label("Chaque utilisateur de cet ordinateur a son propre profil : ses comptes, ses "
                + "sauvegardes et son mot de passe maître. Un utilisateur ne peut pas ouvrir les données d'un autre. "
                + "Pour en ajouter un : « Changer d'utilisateur », puis « Ajouter un utilisateur ». Seuls les noms "
                + "des profils sont visibles avant le déverrouillage.", "muted");
        how.setWrapText(true);
        Button delete = new Button("Supprimer ce profil…");
        delete.getStyleClass().add("danger");
        delete.setOnAction(e -> deleteProfile());
        return Widgets.section("Utilisateur", labeled("Nom affiché", Widgets.row(name, rename)),
                Widgets.row(switchUser, Widgets.spacer(), delete), how);
    }

    /** Suppression definitive du profil courant : confirmation, puis mot de passe maitre du profil. */
    private void deleteProfile() {
        String user = ctx.security().profileName();
        if (!Dialogs.confirm(window(), "Supprimer le profil « " + user + " »",
                "Toutes les données de « " + user + " » seront définitivement effacées de cet ordinateur : comptes, "
                        + "opérations, réglages, mot de passe maître et sauvegardes automatiques. Cette action est "
                        + "irréversible.\n\nLes sauvegardes exportées ailleurs ne sont pas concernées. Les autres "
                        + "utilisateurs ne sont pas touchés.", "Continuer")) {
            return;
        }
        PasswordPromptDialog.ask(window(), "Confirmer la suppression",
                "Saisissez le mot de passe maître de « " + user + " » pour supprimer définitivement ce profil.")
                .ifPresent(password -> {
                    try {
                        if (!ctx.security().checkPassword(password)) {
                            Dialogs.error(window(), new IllegalArgumentException(
                                    "Mot de passe incorrect : le profil n'a pas été supprimé."));
                            return;
                        }
                        ctx.security().deleteCurrentProfile();
                    } catch (Exception ex) {
                        Dialogs.error(window(), new IllegalStateException(
                                "La suppression n'a pas pu aller au bout : " + ex.getMessage(), ex));
                    } finally {
                        java.util.Arrays.fill(password, '\0');
                    }
                });
    }

    private VBox securitySection() {
        ComboBox<Choice<Integer>> autoLock = new ComboBox<>();
        autoLock.getItems().setAll(List.of(new Choice<>(0, "Jamais"), new Choice<>(1, "Après 1 minute"),
                new Choice<>(5, "Après 5 minutes"), new Choice<>(15, "Après 15 minutes"),
                new Choice<>(30, "Après 30 minutes"), new Choice<>(60, "Après 1 heure")));
        Widgets.select(autoLock, ctx.security().autoLockMinutesProperty().get());
        if (autoLock.getValue() == null) {
            autoLock.getSelectionModel().select(2);
        }
        autoLock.setOnAction(e -> ctx.security().autoLockMinutesProperty().set(Widgets.selected(autoLock)));

        Button lock = new Button("Verrouiller maintenant");
        lock.getStyleClass().add("secondary");
        lock.setOnAction(e -> ctx.security().lockNow());
        Button change = new Button("Changer le mot de passe maître…");
        change.getStyleClass().add("secondary");
        change.setOnAction(e -> new ChangePasswordDialog(ctx).showAndWait().ifPresent(ok ->
                Dialogs.info(window(), "Mot de passe modifié", "Votre nouveau mot de passe maître est en place.")));
        Button recovery = new Button("Générer une nouvelle clé de récupération…");
        recovery.getStyleClass().add("ghost");
        recovery.setOnAction(e -> regenerateRecoveryKey());

        Label how = Widgets.label("Vos données sont chiffrées sur le disque (SQLCipher, AES-256). Leur clé est protégée par "
                + "votre mot de passe maître (Argon2id) et par votre clé de récupération ; aucun mot de passe n'est stocké. "
                + "Au verrouillage, la clé est effacée de la mémoire. Raccourci : Ctrl+L.", "muted");
        how.setWrapText(true);
        return Widgets.section("Sécurité",
                labeled("Verrouillage automatique en cas d'inactivité", autoLock),
                Widgets.row(lock, change, recovery),
                how);
    }

    private void regenerateRecoveryKey() {
        PasswordPromptDialog.ask(window(), "Nouvelle clé de récupération",
                "L'ancienne clé de récupération cessera de fonctionner. Confirmez avec votre mot de passe maître.")
                .ifPresent(password -> {
                    try {
                        char[] key = ctx.security().vault().regenerateRecoveryKey(password);
                        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
                        dialog.setTitle("Nouvelle clé de récupération");
                        Dialogs.style(dialog.getDialogPane(), window(), dialog);
                        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
                        javafx.scene.Node closeButton = dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CLOSE);
                        closeButton.setVisible(false);
                        closeButton.setManaged(false);
                        dialog.getDialogPane().setContent(RecoveryKeyPanel.build(key, "Terminer", () -> {
                            java.util.Arrays.fill(key, '\0');
                            dialog.close();
                        }));
                        dialog.showAndWait();
                    } catch (InvalidSecretException ex) {
                        Dialogs.error(window(), new IllegalArgumentException("Mot de passe incorrect."));
                    } catch (IOException ex) {
                        Dialogs.error(window(), ex);
                    } finally {
                        java.util.Arrays.fill(password, '\0');
                    }
                });
    }

    private VBox privacySection() {
        CheckBox privacy = new CheckBox("Masquer tous les montants (mode confidentialité)");
        privacy.selectedProperty().bindBidirectional(ctx.formats().privacyProperty());
        Label hint = Widgets.label("Raccourci : Ctrl+M, utilisable depuis n'importe quel écran (partage d'écran, lieu public).", "muted");
        Label local = Widgets.label("Les données restent sur cet ordinateur : aucune donnée financière n'est envoyée "
                + "à un serveur, aucune télémétrie.", "muted");
        local.setWrapText(true);
        return Widgets.section("Confidentialité", privacy, hint, local);
    }

    /** Place occupee par les justificatifs : ils sont dans la base, donc dans chaque sauvegarde. */
    private Label attachmentsUsage() {
        var usage = ctx.services().attachments().usage();
        Label label = Widgets.label(usage.count() == 0
                ? "Justificatifs : aucun. Ceux que vous joindrez aux opérations seront chiffrés avec vos données "
                        + "et inclus dans chaque sauvegarde."
                : "Justificatifs : " + usage.count() + (usage.count() > 1 ? " fichiers, " : " fichier, ")
                        + com.financeapp.desktop.ui.common.AttachmentsPane.size(usage.bytes())
                        + " — chiffrés avec vos données et inclus dans chaque sauvegarde.", "muted");
        label.setWrapText(true);
        return label;
    }

    private VBox backupSection() {
        SettingsService settings = ctx.services().settings();
        BackupService backups = ctx.services().backups();

        CheckBox auto = new CheckBox("Sauvegarde automatique à chaque fermeture de l'application");
        auto.setSelected(settings.autoBackupEnabled());
        auto.setOnAction(e -> settings.setAutoBackupEnabled(auto.isSelected()));
        Spinner<Integer> keep = new Spinner<>(SettingsService.MIN_BACKUPS_KEPT, 100, settings.autoBackupKeep());
        keep.setEditable(true);
        keep.setPrefWidth(90);
        keep.valueProperty().addListener((o, old, v) -> settings.setAutoBackupKeep(v));

        Button now = new Button("Sauvegarder maintenant");
        now.getStyleClass().add("secondary");
        now.setOnAction(e -> run(() -> {
            backups.createAutomaticBackup(settings.autoBackupKeep());
            refresh();
        }));
        Button export = new Button("Exporter une sauvegarde…");
        export.getStyleClass().add("secondary");
        export.setOnAction(e -> exportBackup());
        Button exportWeb = new Button("Exporter pour la version web…");
        exportWeb.getStyleClass().add("ghost");
        exportWeb.setTooltip(new javafx.scene.control.Tooltip("Fichier chiffré à restaurer sur le site FinanceApp"));
        exportWeb.setOnAction(e -> exportForWeb());
        Button restore = new Button("Restaurer depuis un fichier…");
        restore.getStyleClass().add("ghost");
        restore.setOnAction(e -> {
            FileChooser chooser = chooser("Choisir une sauvegarde à restaurer");
            File file = chooser.showOpenDialog(window());
            if (file != null) {
                restore(file);
            }
        });

        VBox list = new VBox(4);
        try {
            List<BackupInfo> infos = backups.listBackups();
            for (BackupInfo info : infos.stream().limit(15).toList()) {
                Button restoreThis = new Button("Restaurer");
                restoreThis.getStyleClass().addAll("ghost", "compact");
                restoreThis.setOnAction(e -> restore(info.file().toFile()));
                String when = STAMP.format(LocalDateTime.ofInstant(info.modifiedAt(), ZoneId.systemDefault()));
                String detail = info.sameKey()
                        ? info.accounts() + " compte(s) · " + info.transactions() + " opération(s) · " + (info.sizeBytes() / 1024) + " Ko"
                        : "chiffrée avec une autre clé : mot de passe de la sauvegarde requis · " + (info.sizeBytes() / 1024) + " Ko";
                HBox row = Widgets.row(Widgets.label(when, "op-label"),
                        Widgets.badge(info.automatic() ? "auto" : "manuelle", info.automatic() ? "neutral" : "info"),
                        Widgets.label(detail, "op-detail"),
                        Widgets.spacer(), restoreThis);
                row.getStyleClass().add("op-row");
                list.getChildren().add(row);
            }
            if (infos.isEmpty()) {
                list.getChildren().add(Widgets.emptyState("Aucune sauvegarde pour l'instant."));
            }
        } catch (IOException e) {
            list.getChildren().add(Widgets.emptyState("Impossible de lire le dossier des sauvegardes."));
        }

        VBox box = Widgets.section("Sauvegardes",
                auto,
                labeled("Nombre de sauvegardes automatiques conservées", keep),
                Widgets.row(now, export, restore),
                exportWeb,
                Widgets.label("Dossier : " + ctx.services().directories().backupsDir(), "muted"),
                attachmentsUsage(),
                list);
        if (backups.hasPendingRestore()) {
            Button cancel = new Button("Annuler la restauration");
            cancel.getStyleClass().addAll("ghost", "compact");
            cancel.setOnAction(e -> run(() -> {
                backups.cancelPendingRestore();
                refresh();
            }));
            box.getChildren().add(1, Widgets.row(Widgets.badge("Restauration programmée : elle sera appliquée au prochain démarrage", "warning"), cancel));
        }
        return box;
    }

    /** Resultat d'une verification : nouvelle version, a jour, ou erreur reseau (jamais d'exception affichee). */
    private record UpdateResult(java.util.Optional<com.financeapp.core.update.AvailableRelease> release, String error) {
    }

    private VBox updateSection() {
        var updates = ctx.services().updates();
        CheckBox auto = new CheckBox("Vérifier les nouvelles versions à l'ouverture (au plus une fois par jour)");
        auto.setSelected(updates.autoCheckEnabled());
        auto.setOnAction(e -> updates.setAutoCheckEnabled(auto.isSelected()));
        Label result = Widgets.label("", "op-detail");
        result.setWrapText(true);
        Button open = new Button("Voir la nouvelle version");
        open.getStyleClass().add("primary");
        open.setVisible(false);
        open.setManaged(false);
        Button check = new Button("Vérifier maintenant");
        check.getStyleClass().add("secondary");
        check.setOnAction(e -> {
            check.setDisable(true);
            open.setVisible(false);
            open.setManaged(false);
            result.setText("Vérification en cours…");
            UiAsync.load(() -> {
                try {
                    return new UpdateResult(updates.checkNow(), null);
                } catch (java.io.IOException ex) {
                    return new UpdateResult(java.util.Optional.empty(), ex.getMessage());
                }
            }, r -> {
                check.setDisable(false);
                if (r.error() != null) {
                    result.setText("Vérification impossible (pas de connexion ?) : " + r.error());
                } else if (r.release().isPresent()) {
                    var rel = r.release().get();
                    result.setText("Nouvelle version disponible : " + rel.version()
                            + (rel.publishedOn() == null ? "" : ", publiée le " + Formats.date(rel.publishedOn())) + ".");
                    open.setOnAction(x -> com.financeapp.desktop.ui.common.Browser.open(rel.pageUrl()));
                    open.setVisible(true);
                    open.setManaged(true);
                } else {
                    result.setText("Vous utilisez la dernière version.");
                }
            });
        });
        Label how = Widgets.label("La vérification interroge la page des versions publiées sur GitHub. Aucune donnée "
                + "(financière ou personnelle) n'est envoyée, et rien n'est téléchargé ni installé automatiquement : "
                + "vous choisissez d'ouvrir la page de la nouvelle version. Désactivée par défaut.", "muted");
        how.setWrapText(true);
        return Widgets.section("Mises à jour",
                Widgets.label("Version installée : " + updates.currentVersion(), "op-label"),
                auto, Widgets.row(check, open), result, how);
    }

    private VBox aboutSection() {
        var p = ctx.services().properties();
        return Widgets.section("À propos",
                Widgets.label(p.name() + " — version " + p.version(), "op-label"),
                Widgets.label("Données : " + ctx.services().directories().root(), "muted"),
                Widgets.label("Journaux techniques (sans données financières) : " + ctx.services().directories().logsDir(), "muted"));
    }

    /** Fichier chiffre au format de la version web, avec sa cle de recuperation. */
    private void exportForWeb() {
        new WebExportDialog(ctx).showAndWait().ifPresent(password -> {
            try {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Exporter pour la version web");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sauvegarde web (*.json)", "*.json"));
                chooser.setInitialFileName(ctx.services().properties().id() + "-web-"
                        + DateTimeFormatter.ofPattern("yyyy-MM-dd").format(ctx.services().planning().today()) + ".json");
                File file = chooser.showSaveDialog(window());
                if (file == null) {
                    return;
                }
                var export = ctx.services().webExport().export();
                var result = new com.financeapp.infra.security.WebBackupWriter()
                        .write(ctx.security().profileName(), export.json(), password);
                java.nio.file.Files.writeString(file.toPath(), result.json(), java.nio.charset.StandardCharsets.UTF_8);
                var r = export.report();
                String details = r.accounts() + " compte(s), " + r.transactions() + " opération(s), " + r.rules()
                        + " récurrence(s), " + r.categories() + " catégorie(s) exportés dans « " + file.getName() + " »."
                        + (r.skippedAccounts() > 0 ? "\nNon exportés (autre devise) : " + r.skippedAccounts()
                                + " compte(s), " + r.skippedTransactions() + " opération(s), " + r.skippedRules()
                                + " récurrence(s)." : "")
                        + (r.splitsSimplified() > 0 ? "\n" + r.splitsSimplified()
                                + " ventilation(s) simplifiée(s) : détail dans le commentaire." : "");
                char[] key = result.recoveryKey().toCharArray();
                javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
                dialog.setTitle("Exporté pour la version web");
                Dialogs.style(dialog.getDialogPane(), window(), dialog);
                dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
                javafx.scene.Node closeButton = dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CLOSE);
                closeButton.setVisible(false);
                closeButton.setManaged(false);
                Label summary = Widgets.label(details + "\nSur le site : « Restaurer une sauvegarde », puis le mot de passe "
                        + "choisi. Si vous l'oubliez, la clé ci-dessous ouvrira ce profil web.", "hint");
                summary.setWrapText(true);
                summary.setMaxWidth(520);
                dialog.getDialogPane().setContent(new VBox(14, summary, RecoveryKeyPanel.build(key, "Terminer", () -> {
                    java.util.Arrays.fill(key, '\0');
                    dialog.close();
                })));
                dialog.showAndWait();
            } catch (IOException | java.security.GeneralSecurityException | RuntimeException ex) {
                Dialogs.error(window(), ex);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        });
    }

    private void exportBackup() {
        FileChooser chooser = chooser("Exporter une sauvegarde");
        chooser.setInitialFileName(ctx.services().properties().id() + "-sauvegarde-"
                + DateTimeFormatter.ofPattern("yyyy-MM-dd").format(ctx.services().planning().today()) + ".db");
        File file = chooser.showSaveDialog(window());
        if (file != null) {
            run(() -> {
                BackupInfo info = ctx.services().backups().exportTo(file.toPath());
                Dialogs.info(window(), "Sauvegarde exportée", "Sauvegarde vérifiée et enregistrée :\n" + info.file());
            });
        }
    }

    private void restore(File file) {
        BackupService backups = ctx.services().backups();
        BackupService.RestoreRequirement requirement;
        try {
            requirement = backups.restoreRequirement(file.toPath());
        } catch (InvalidBackupException e) {
            Dialogs.error(window(), new IllegalArgumentException(e.getMessage()));
            return;
        }
        if (!Dialogs.confirm(window(), "Restaurer une sauvegarde",
                "Les données actuelles seront remplacées par celles de « " + file.getName() + " » au prochain démarrage.\n"
                        + "Une copie de sécurité des données actuelles sera faite automatiquement.", "Programmer la restauration")) {
            return;
        }
        char[] password = null;
        if (requirement == BackupService.RestoreRequirement.BACKUP_PASSWORD) {
            password = PasswordPromptDialog.ask(window(), "Mot de passe de la sauvegarde",
                    "Cette sauvegarde a été chiffrée avec une autre clé (autre installation, ou avant une réinitialisation). "
                            + "Saisissez le mot de passe maître en vigueur lors de sa création.").orElse(null);
            if (password == null) {
                return;
            }
        }
        char[] secret = password;
        run(() -> {
            try {
                BackupInfo info = backups.scheduleRestore(file.toPath(), secret);
                Dialogs.info(window(), "Restauration programmée", "Sauvegarde valide (" + info.accounts() + " compte(s), "
                        + info.transactions() + " opération(s)).\nFermez puis relancez l'application pour l'appliquer."
                        + (secret != null ? "\nVous déverrouillerez ensuite avec le mot de passe de cette sauvegarde." : ""));
                refresh();
            } finally {
                if (secret != null) {
                    java.util.Arrays.fill(secret, '\0');
                }
            }
        });
    }

    private FileChooser chooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sauvegarde (*.db)", "*.db"));
        return chooser;
    }

    private HBox labeled(String label, javafx.scene.Node control) {
        return Widgets.row(Widgets.label(label, "form-label"), Widgets.spacer(), control);
    }

    private interface IoAction {
        void run() throws IOException, InvalidBackupException;
    }

    private void run(IoAction action) {
        try {
            action.run();
        } catch (InvalidBackupException e) {
            Dialogs.error(window(), new IllegalArgumentException(e.getMessage()));
        } catch (IOException | RuntimeException e) {
            Dialogs.error(window(), e);
        }
    }
}
