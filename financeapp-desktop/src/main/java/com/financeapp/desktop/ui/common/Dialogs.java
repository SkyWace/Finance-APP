package com.financeapp.desktop.ui.common;

import com.financeapp.core.service.BusinessException;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Boites de dialogue standard, avec le theme de l'application. */
public final class Dialogs {

    private static final Logger log = LoggerFactory.getLogger(Dialogs.class);

    private Dialogs() {
    }

    /**
     * Erreur metier : message tel quel. Erreur technique : message generique,
     * detail uniquement dans le log (qui ne contient pas de donnees financieres).
     */
    public static void error(Window owner, Throwable e) {
        String message;
        if (e instanceof BusinessException || e instanceof IllegalArgumentException) {
            message = e.getMessage();
        } else {
            log.error("Erreur inattendue", e);
            message = "Une erreur inattendue s'est produite. Le détail technique a été enregistré dans le fichier de log.";
        }
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.setTitle("Opération impossible");
        style(alert.getDialogPane(), owner, alert);
        alert.showAndWait();
    }

    public static void info(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(null);
        alert.setTitle(title);
        style(alert.getDialogPane(), owner, alert);
        alert.showAndWait();
    }

    public static boolean confirm(Window owner, String title, String message, String confirmLabel) {
        ButtonType ok = new ButtonType(confirmLabel, ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Annuler", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ok, cancel);
        alert.setHeaderText(null);
        alert.setTitle(title);
        style(alert.getDialogPane(), owner, alert);
        return alert.showAndWait().filter(ok::equals).isPresent();
    }

    public static void style(DialogPane pane, Window owner, javafx.scene.control.Dialog<?> dialog) {
        if (owner != null) {
            dialog.initOwner(owner);
        }
        pane.getStylesheets().add(Objects.requireNonNull(
                Dialogs.class.getResource("/com/financeapp/desktop/theme.css")).toExternalForm());
    }
}
