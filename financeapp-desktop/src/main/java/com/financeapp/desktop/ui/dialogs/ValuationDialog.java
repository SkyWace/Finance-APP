package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountValuation;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/** Mise a jour de la valeur d'une epargne (releve d'un livret, valeur d'un PEA, d'une assurance-vie...). */
public final class ValuationDialog extends FormDialog<AccountValuation> {

    private final Account account;
    private final TextField value = new TextField();
    private final DatePicker date;

    public ValuationDialog(UiContext ctx, Account account) {
        super(ctx, "Mettre à jour la valeur — " + account.name(), "Enregistrer");
        this.account = account;
        date = Widgets.datePicker(ctx.services().planning().today());
        value.setPromptText("Valeur figurant sur le relevé");
        addRow("Valeur actuelle", Widgets.label(ctx.formats().money(ctx.services().accounts().balanceOf(account.id())),
                "op-label"));
        addRow("Nouvelle valeur", value);
        addRow("À la date du", date);
        Label hint = Widgets.label("Cette valeur remplace le solde calculé ; les versements et retraits datés après "
                + "s'y ajoutent. Les intérêts et plus-values ne sont pas comptés comme des revenus : ils n'apparaissent "
                + "que dans votre patrimoine.", "hint");
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        addFullRow(hint);
        setOnShown(e -> value.requestFocus());
    }

    @Override
    protected AccountValuation submit() {
        return ctx.services().accounts().recordValuation(account.id(),
                require(Widgets.dateValue(date), "Date invalide"), requireAmount(value, true));
    }
}
