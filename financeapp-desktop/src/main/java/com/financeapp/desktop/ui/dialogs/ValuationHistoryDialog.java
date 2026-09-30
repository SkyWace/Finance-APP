package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountValuation;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/** Historique des valeurs saisies d'une epargne, avec suppression d'une saisie erronee. */
public final class ValuationHistoryDialog extends Dialog<Void> {

    private final UiContext ctx;
    private final Account account;
    private final VBox list = new VBox(2);
    private boolean changed;

    public ValuationHistoryDialog(UiContext ctx, Account account) {
        this.ctx = ctx;
        this.account = account;
        setTitle("Historique des valeurs — " + account.name());
        setHeaderText(null);
        Dialogs.style(getDialogPane(), ctx.window(), this);
        var hint = Widgets.label("Supprimer une valeur revient à la précédente "
                + "(ou au solde calculé d'après les opérations).", "hint");
        hint.setWrapText(true);
        getDialogPane().setContent(new VBox(10, list, hint));
        getDialogPane().getButtonTypes().add(new ButtonType("Fermer", ButtonBar.ButtonData.CANCEL_CLOSE));
        getDialogPane().setPrefWidth(520);
        render();
    }

    public boolean changed() {
        return changed;
    }

    private void render() {
        List<AccountValuation> values = ctx.services().accounts().valuations(account.id());
        list.getChildren().clear();
        AccountValuation newer = null;
        for (int i = values.size() - 1; i >= 0; i--) {
            AccountValuation v = values.get(i);
            String change = "";
            if (i < values.size() - 1) {
                var delta = v.value().minus(values.get(i + 1).value());
                change = delta.isZero() ? "  (=)" : "  (" + ctx.formats().signed(delta) + ")";
            }
            Button delete = new Button("Supprimer");
            delete.getStyleClass().addAll("ghost", "compact");
            delete.setOnAction(e -> {
                ctx.services().accounts().deleteValuation(v.id());
                changed = true;
                render();
            });
            HBox row = Widgets.row(Widgets.label(Formats.date(v.date()), "op-date"),
                    Widgets.label(ctx.formats().money(v.value()) + change, "op-label"), Widgets.spacer(), delete);
            row.getStyleClass().add("op-row");
            list.getChildren().addFirst(row);
        }
        if (values.isEmpty()) {
            list.getChildren().add(Widgets.emptyState("Aucune valeur saisie : le solde est calculé d'après les opérations."));
        }
    }
}
