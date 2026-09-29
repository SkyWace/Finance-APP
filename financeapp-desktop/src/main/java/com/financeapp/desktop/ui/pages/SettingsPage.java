package com.financeapp.desktop.ui.pages;

import com.financeapp.core.available.HorizonType;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.infra.backup.BackupInfo;
import com.financeapp.infra.backup.BackupService;
import com.financeapp.infra.backup.InvalidBackupException;
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
        content.getChildren().setAll(generalSection(), privacySection(), backupSection(), aboutSection());
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

    private VBox privacySection() {
        CheckBox privacy = new CheckBox("Masquer tous les montants (mode confidentialité)");
        privacy.selectedProperty().bindBidirectional(ctx.formats().privacyProperty());
        Label hint = Widgets.label("Raccourci : Ctrl+M, utilisable depuis n'importe quel écran (partage d'écran, lieu public).", "muted");
        Label local = Widgets.label("Les données restent sur cet ordinateur : aucune donnée financière n'est envoyée "
                + "à un serveur, aucune télémétrie. Le mot de passe maître et le chiffrement de la base sont prévus "
                + "dans une prochaine version.", "muted");
        local.setWrapText(true);
        return Widgets.section("Confidentialité", privacy, hint, local);
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
                HBox row = Widgets.row(Widgets.label(when, "op-label"),
                        Widgets.badge(info.automatic() ? "auto" : "manuelle", info.automatic() ? "neutral" : "info"),
                        Widgets.label(info.accounts() + " compte(s) · " + info.transactions() + " opération(s) · "
                                + (info.sizeBytes() / 1024) + " Ko", "op-detail"),
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
                Widgets.label("Dossier : " + ctx.services().directories().backupsDir(), "muted"),
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

    private VBox aboutSection() {
        var p = ctx.services().properties();
        return Widgets.section("À propos",
                Widgets.label(p.name() + " — version " + p.version(), "op-label"),
                Widgets.label("Données : " + ctx.services().directories().root(), "muted"),
                Widgets.label("Journaux techniques (sans données financières) : " + ctx.services().directories().logsDir(), "muted"));
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
        if (!Dialogs.confirm(window(), "Restaurer une sauvegarde",
                "Les données actuelles seront remplacées par celles de « " + file.getName() + " » au prochain démarrage.\n"
                        + "Une copie de sécurité des données actuelles sera faite automatiquement.", "Programmer la restauration")) {
            return;
        }
        run(() -> {
            BackupInfo info = ctx.services().backups().scheduleRestore(file.toPath());
            Dialogs.info(window(), "Restauration programmée", "Sauvegarde valide (" + info.accounts() + " compte(s), "
                    + info.transactions() + " opération(s)).\nFermez puis relancez l'application pour l'appliquer.");
            refresh();
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
