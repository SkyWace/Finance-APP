package com.financeapp.desktop.ui.security;

import com.financeapp.desktop.ui.common.Widgets;
import javafx.animation.PauseTransition;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Affichage unique de la cle de recuperation. L'utilisateur doit confirmer
 * l'avoir conservee avant de continuer. Si elle est copiee, le
 * presse-papiers est vide automatiquement apres une minute.
 */
public final class RecoveryKeyPanel {

    private RecoveryKeyPanel() {
    }

    public static VBox build(char[] recoveryKey, String continueLabel, Runnable onContinue) {
        String key = new String(recoveryKey);
        Label title = Widgets.label("Votre clé de récupération", "lock-title");
        Label explain = Widgets.label("""
                Si vous oubliez votre mot de passe maître, cette clé est le SEUL moyen de retrouver vos données. \
                Notez-la sur papier ou dans un gestionnaire de mots de passe, et conservez-la en lieu sûr. \
                Elle ne sera plus jamais affichée.""", "lock-text");
        explain.setWrapText(true);
        Label value = Widgets.label(key, "recovery-key");
        value.setWrapText(true);

        Button copy = new Button("Copier");
        copy.getStyleClass().add("ghost");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(key);
            Clipboard.getSystemClipboard().setContent(content);
            copy.setText("Copiée — effacée du presse-papiers dans 1 min");
            PauseTransition clear = new PauseTransition(Duration.seconds(60));
            clear.setOnFinished(ev -> {
                if (key.equals(Clipboard.getSystemClipboard().getString())) {
                    Clipboard.getSystemClipboard().clear();
                }
                copy.setText("Copier");
            });
            clear.play();
        });

        CheckBox confirm = new CheckBox("J'ai conservé ma clé de récupération en lieu sûr");
        Button next = new Button(continueLabel);
        next.getStyleClass().add("primary");
        next.setDefaultButton(true);
        next.disableProperty().bind(confirm.selectedProperty().not());
        next.setOnAction(e -> onContinue.run());

        VBox box = new VBox(16, title, explain, value, copy, confirm, next);
        box.getStyleClass().add("lock-card");
        box.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        return box;
    }
}
