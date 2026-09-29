package com.financeapp.desktop.ui.dialogs;

import com.financeapp.desktop.ui.common.Dialogs;
import javafx.application.Platform;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

/** Demande ponctuelle d'un mot de passe ; renvoie un tableau de caracteres a effacer apres usage. */
public final class PasswordPromptDialog {

    private PasswordPromptDialog() {
    }

    public static Optional<char[]> ask(Window owner, String title, String message) {
        Dialog<char[]> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        Dialogs.style(dialog.getDialogPane(), owner, dialog);
        PasswordField field = new PasswordField();
        field.setPromptText("Mot de passe");
        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(420);
        dialog.getDialogPane().setContent(new VBox(12, text, field));
        ButtonType ok = new ButtonType("Valider", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, new ButtonType("Annuler", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.setResultConverter(b -> {
            char[] value = b == ok && !field.getText().isEmpty() ? field.getText().toCharArray() : null;
            field.clear();
            return value;
        });
        Platform.runLater(field::requestFocus);
        return dialog.showAndWait();
    }
}
