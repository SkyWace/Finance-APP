package com.financeapp.desktop.ui.common;

import com.financeapp.core.service.BusinessException;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Formulaire modal. La validation et l'enregistrement se font au clic sur le
 * bouton principal ({@link #submit()}) ; en cas d'erreur metier, le message
 * s'affiche dans le formulaire et la fenetre reste ouverte.
 */
public abstract class FormDialog<R> extends Dialog<R> {

    protected final UiContext ctx;
    private final GridPane grid = new GridPane();
    private final Label error = Widgets.label("", "form-error");
    private int row;
    private R result;

    protected FormDialog(UiContext ctx, String title, String okLabel) {
        this.ctx = ctx;
        setTitle(title);
        setHeaderText(null);
        Dialogs.style(getDialogPane(), ctx.window(), this);
        getDialogPane().getStyleClass().add("form-dialog");

        grid.setHgap(14);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints(150);
        ColumnConstraints fields = new ColumnConstraints(320, 360, Double.MAX_VALUE);
        fields.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labels, fields);
        error.setWrapText(true);
        error.setMaxWidth(480);
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        error.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        error.textProperty().addListener((o, a, b) -> fitToContent()); // message entier, jamais tronque
        getDialogPane().setContent(new VBox(14, grid, error));

        ButtonType ok = new ButtonType(okLabel, ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(ok, new ButtonType("Annuler", ButtonBar.ButtonData.CANCEL_CLOSE));
        getDialogPane().lookupButton(ok).addEventFilter(ActionEvent.ACTION, e -> {
            try {
                error.setText("");
                result = submit();
            } catch (BusinessException | IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                e.consume();
            } catch (RuntimeException ex) {
                Dialogs.error(getDialogPane().getScene().getWindow(), ex);
                e.consume();
            }
        });
        setResultConverter(button -> button == ok ? result : null);
    }

    protected void addRow(String label, Node field) {
        Label l = Widgets.label(label, "form-label");
        grid.add(l, 0, row);
        grid.add(field, 1, row);
        GridPane.setHgrow(field, Priority.ALWAYS);
        row++;
    }

    /** Ligne qui peut etre masquee (le libelle suit la visibilite du champ). */
    protected void addOptionalRow(String label, Node field) {
        Label l = Widgets.label(label, "form-label");
        l.visibleProperty().bind(field.visibleProperty());
        l.managedProperty().bind(field.visibleProperty());
        field.managedProperty().bind(field.visibleProperty());
        grid.add(l, 0, row);
        grid.add(field, 1, row);
        row++;
    }

    /** A appeler quand un texte de la fenetre change de hauteur (apercu) : la fenetre s'ajuste. */
    protected void fitToContent() {
        if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null
                && getDialogPane().getScene().getWindow().isShowing()) {
            javafx.application.Platform.runLater(() -> getDialogPane().getScene().getWindow().sizeToScene());
        }
    }

    protected void addFullRow(Node node) {
        if (node instanceof Label l && l.isWrapText()) {
            // Sans cela, un texte sur plusieurs lignes est tronque ("...") dans la grille.
            l.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
        grid.add(node, 0, row, 2, 1);
        row++;
    }

    /** Valide, enregistre et renvoie le resultat ; lever {@link BusinessException} pour signaler une erreur de saisie. */
    protected abstract R submit();

    protected static java.math.BigDecimal requireAmount(javafx.scene.control.TextField field, boolean allowNegative) {
        java.math.BigDecimal value = AmountParser.parse(field.getText())
                .orElseThrow(() -> new BusinessException("Montant invalide : saisissez par exemple 1234,56"));
        if (!allowNegative && value.signum() <= 0) {
            throw new BusinessException("Le montant doit être strictement positif");
        }
        return value;
    }

    protected static <T> T require(T value, String message) {
        if (value == null) {
            throw new BusinessException(message);
        }
        return value;
    }
}
