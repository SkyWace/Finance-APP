package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.service.TransferDraft;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.time.LocalDate;
import java.util.List;

/** Virement interne : une seule saisie, deux lignes liees en base, impact patrimoine nul. */
public final class TransferDialog extends FormDialog<List<Transaction>> {

    private final String transferGroup;
    private final ComboBox<Choice<Long>> from;
    private final ComboBox<Choice<Long>> to;
    private final DatePicker date;
    private final TextField label = new TextField("Virement");
    private final TextField amount = new TextField();
    private final ComboBox<TransactionStatus> status = new ComboBox<>();
    private final TextField note = new TextField();

    /** @param anyLeg une jambe du virement a modifier, ou {@code null} pour un nouveau virement */
    public TransferDialog(UiContext ctx, Transaction anyLeg) {
        super(ctx, anyLeg == null ? "Nouveau virement interne" : "Modifier le virement", "Enregistrer");
        LocalDate today = ctx.services().planning().today();
        List<Transaction> legs = anyLeg == null ? null : ctx.services().transactions().legsOf(anyLeg.transferGroup());
        this.transferGroup = anyLeg == null ? null : anyLeg.transferGroup();
        var accounts = ctx.services().accounts().findAll();
        from = Widgets.accountCombo(accounts, legs == null ? null : legs.get(0).accountId());
        to = Widgets.accountCombo(accounts, legs == null ? null : legs.get(1).accountId());
        if (legs == null && to.getItems().size() > 1) {
            to.getSelectionModel().select(1);
        }
        date = Widgets.datePicker(legs == null ? today : legs.get(0).date());
        status.getItems().setAll(TransactionStatus.values());
        status.setValue(legs == null ? TransactionStatus.COMPLETED : legs.get(0).status());
        status.setMaxWidth(Double.MAX_VALUE);
        date.valueProperty().addListener((o, old, d) -> {
            if (legs == null && d != null) {
                status.setValue(d.isAfter(today) ? TransactionStatus.PLANNED : TransactionStatus.COMPLETED);
            }
        });
        if (legs != null) {
            label.setText(legs.get(0).label());
            amount.setText(AmountParser.toEditable(legs.get(1).amount().amount()));
            note.setText(legs.get(0).note());
        }
        amount.setPromptText("0,00");

        addRow("Depuis", from);
        addRow("Vers", to);
        addRow("Date", date);
        addRow("Libellé", label);
        addRow("Montant", amount);
        addRow("Statut", status);
        addRow("Commentaire", note);
        Label hint = Widgets.label("Un virement interne n'est compté ni comme une dépense ni comme un revenu.", "hint");
        hint.setWrapText(true);
        addFullRow(hint);
        setOnShown(e -> amount.requestFocus());
    }

    @Override
    protected List<Transaction> submit() {
        TransferDraft draft = new TransferDraft(
                require(Widgets.selected(from), "Choisissez le compte débité"),
                require(Widgets.selected(to), "Choisissez le compte crédité"),
                require(Widgets.dateValue(date), "Date invalide"),
                label.getText().isBlank() ? "Virement" : label.getText(),
                requireAmount(amount, false),
                status.getValue(),
                note.getText());
        return transferGroup == null
                ? ctx.services().transactions().createTransfer(draft)
                : ctx.services().transactions().updateTransfer(transferGroup, draft);
    }
}
