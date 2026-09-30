package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.banksync.BankSyncCredentials;
import com.financeapp.core.service.BusinessException;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Activation de la synchronisation : identifiant de l'application enregistree
 * par l'utilisateur chez Enable Banking, sa cle privee (.pem) et l'adresse de
 * retour declaree. La cle est stockee uniquement dans la base chiffree.
 */
public final class BankSyncSetupDialog extends FormDialog<Boolean> {

    public static final String DEFAULT_REDIRECT = "https://localhost/financeapp";

    private final TextField applicationId = new TextField();
    private final TextField redirectUrl = new TextField(DEFAULT_REDIRECT);
    private final Label keyStatus = Widgets.label("Aucun fichier choisi", "op-detail");
    private final CheckBox consent;
    private String pem;

    public BankSyncSetupDialog(UiContext ctx) {
        super(ctx, "Synchronisation bancaire — paramètres", "Activer");
        String provider = ctx.services().bankSync().providerName();
        consent = new CheckBox("J'accepte que les opérations des comptes que j'autoriserai soient transmises par "
                + provider + " à cet ordinateur.");
        consent.setWrapText(true);
        consent.setMaxWidth(480);
        ctx.services().bankSync().credentials().ifPresent(c -> {
            applicationId.setText(c.applicationId());
            redirectUrl.setText(c.redirectUrl());
            pem = c.privateKeyPem();
            keyStatus.setText("Clé déjà enregistrée (chiffrée)");
            consent.setSelected(true);
        });

        Label steps = Widgets.label("""
                Préparation, une seule fois, sur enablebanking.com :
                1. Créez un compte et enregistrez une application en environnement « Production ».
                2. Déclarez l'adresse de retour ci-dessous (HTTPS ; https://localhost/financeapp convient).
                3. Liez vos comptes à l'application (bouton « Activate by linking accounts ») :
                   le mode restreint, gratuit, n'accède qu'aux comptes liés.
                4. Conservez l'identifiant de l'application et le fichier de clé privée (.pem).""", "hint");
        steps.setWrapText(true);
        steps.setMaxWidth(480);
        addFullRow(steps);

        applicationId.setPromptText("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Button choose = new Button("Choisir le fichier .pem…");
        choose.getStyleClass().add("secondary");
        choose.setOnAction(e -> chooseKey());
        choose.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        addRow("Application ID", applicationId);
        addRow("Clé privée", Widgets.row(choose, keyStatus));
        addRow("Adresse de retour", redirectUrl);
        addFullRow(consent);

        Label privacy = Widgets.label("Vos identifiants bancaires ne sont jamais demandés : vous vous authentifiez chez "
                + "votre banque. Aucune donnée ne passe par un serveur FinanceApp. Lecture seule : aucun paiement "
                + "n'est possible. Vous pouvez tout désactiver et effacer à tout moment.", "hint");
        privacy.setWrapText(true);
        privacy.setMaxWidth(480);
        addFullRow(privacy);
        setOnShown(e -> applicationId.requestFocus());
    }

    private void chooseKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Clé privée de l'application");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Clé PEM (*.pem)", "*.pem", "*.key", "*.txt"));
        File file = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            if (Files.size(file.toPath()) > 64 * 1024) {
                keyStatus.setText("Fichier trop volumineux pour une clé");
                return;
            }
            pem = Files.readString(file.toPath(), StandardCharsets.US_ASCII);
            keyStatus.setText("Clé lue (non affichée)");
            if (applicationId.getText().isBlank() && file.getName().endsWith(".pem")) {
                // Enable Banking nomme le fichier d'apres l'identifiant de l'application.
                applicationId.setText(file.getName().substring(0, file.getName().length() - 4));
            }
        } catch (IOException | RuntimeException ex) {
            keyStatus.setText("Fichier illisible");
        }
    }

    @Override
    protected Boolean submit() {
        if (pem == null) {
            throw new BusinessException("Choisissez le fichier de clé privée (.pem) de l'application");
        }
        if (!consent.isSelected()) {
            throw new BusinessException("Cochez la case d'accord pour activer la synchronisation");
        }
        ctx.services().bankSync().enable(new BankSyncCredentials(applicationId.getText(), pem, redirectUrl.getText()));
        return true;
    }
}
