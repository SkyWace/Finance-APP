package com.financeapp.desktop.ui.common;

import com.financeapp.core.service.BusinessException;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
        if (isLocked(e)) {
            return; // verrouillage survenu pendant une operation : rien a signaler
        }
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

    /** Vrai si l'erreur vient d'un acces a la base pendant que l'application est verrouillee. */
    public static boolean isLocked(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof com.financeapp.infra.security.DatabaseLockedException) {
                return true;
            }
        }
        return false;
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
        Theme.apply(pane.getStylesheets());
    }
}
