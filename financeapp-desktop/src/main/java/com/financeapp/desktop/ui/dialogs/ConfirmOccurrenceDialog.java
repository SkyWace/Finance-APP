package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.util.List;

/** Validation d'une occurrence recurrente : la date et le montant reels peuvent differer du prevu. */
public final class ConfirmOccurrenceDialog extends FormDialog<List<Transaction>> {

    private final PlannedItem item;
    private final DatePicker date;
    private final TextField amount = new TextField();

    public ConfirmOccurrenceDialog(UiContext ctx, PlannedItem item) {
        super(ctx, "Valider l'opération", "Valider");
        this.item = item;
        date = Widgets.datePicker(item.date());
        amount.setText(AmountParser.toEditable(item.amount().abs().amount()));
        Label what = Widgets.label(item.label() + " — prévu le " + com.financeapp.desktop.ui.common.Formats.date(item.date()),
                "form-subtitle");
        addFullRow(what);
        addRow("Date réelle", date);
        addRow("Montant réel", amount);
        setOnShown(e -> amount.requestFocus());
    }

    @Override
    protected List<Transaction> submit() {
        return ctx.services().recurring().confirm(item.recurringId(), item.date(),
                require(Widgets.dateValue(date), "Date invalide"), requireAmount(amount, false));
    }
}
