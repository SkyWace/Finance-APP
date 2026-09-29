package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

import java.time.LocalDate;

/**
 * Saisie d'un revenu ou d'une depense. Le statut propose suit la date
 * (futur = prevu, passe = effectue) tant que l'utilisateur ne l'a pas choisi.
 */
public final class TransactionDialog extends FormDialog<Transaction> {

    private final Transaction existing;
    private final ToggleButton expenseToggle = new ToggleButton("Dépense");
    private final ToggleButton incomeToggle = new ToggleButton("Revenu");
    private final ComboBox<Choice<Long>> account;
    private final DatePicker date;
    private final TextField label = new TextField();
    private final TextField amount = new TextField();
    private final ComboBox<Choice<Long>> category = new ComboBox<>();
    private final ComboBox<TransactionStatus> status = new ComboBox<>();
    private final TextArea note = new TextArea();
    private boolean statusTouched;

    public TransactionDialog(UiContext ctx, Transaction existing, TransactionType initialType, Long accountId) {
        super(ctx, existing == null ? "Nouvelle opération" : "Modifier l'opération", "Enregistrer");
        this.existing = existing;
        LocalDate today = ctx.services().planning().today();

        ToggleGroup group = new ToggleGroup();
        expenseToggle.setToggleGroup(group);
        incomeToggle.setToggleGroup(group);
        expenseToggle.getStyleClass().add("segment");
        incomeToggle.getStyleClass().add("segment");
        group.selectedToggleProperty().addListener((o, old, t) -> {
            if (t == null) {
                old.setSelected(true);
            } else {
                refreshCategories();
            }
        });

        account = Widgets.accountCombo(ctx.services().accounts().findAll(),
                existing != null ? Long.valueOf(existing.accountId()) : accountId);
        date = Widgets.datePicker(existing != null ? existing.date() : today);
        status.getItems().setAll(TransactionStatus.values());
        status.setMaxWidth(Double.MAX_VALUE);
        category.setMaxWidth(Double.MAX_VALUE);
        note.setPrefRowCount(2);
        note.setWrapText(true);
        label.setPromptText("Ex. Carrefour");
        amount.setPromptText("0,00");

        if (existing != null) {
            (existing.type() == TransactionType.INCOME ? incomeToggle : expenseToggle).setSelected(true);
            label.setText(existing.label());
            amount.setText(AmountParser.toEditable(existing.amount().abs().amount()));
            status.setValue(existing.status());
            note.setText(existing.note());
            statusTouched = true;
        } else {
            (initialType == TransactionType.INCOME ? incomeToggle : expenseToggle).setSelected(true);
            status.setValue(TransactionStatus.COMPLETED);
        }
        refreshCategories();
        if (existing != null) {
            Widgets.select(category, existing.categoryId());
        }

        status.setOnAction(e -> statusTouched = true);
        date.valueProperty().addListener((o, old, d) -> {
            if (!statusTouched && d != null) {
                status.setValue(d.isAfter(today) ? TransactionStatus.PLANNED : TransactionStatus.COMPLETED);
            }
        });

        addRow("Type", new HBox(0, expenseToggle, incomeToggle));
        addRow("Compte", account);
        addRow("Date", date);
        addRow("Libellé", label);
        addRow("Montant", amount);
        addRow("Catégorie", category);
        addRow("Statut", status);
        addRow("Commentaire", note);
        setOnShown(e -> (existing == null ? amount : label).requestFocus());
    }

    private void refreshCategories() {
        Long current = Widgets.selected(category);
        boolean income = incomeToggle.isSelected();
        category.getItems().setAll(Widgets.categoryChoices(ctx.services().categories().activeTree(), income,
                existing != null ? existing.categoryId() : null));
        Widgets.select(category, current);
        if (category.getValue() == null) {
            category.getSelectionModel().selectFirst();
        }
    }

    @Override
    protected Transaction submit() {
        TransactionDraft draft = new TransactionDraft(
                require(Widgets.selected(account), "Choisissez un compte"),
                require(Widgets.dateValue(date), "Date invalide"),
                label.getText(),
                requireAmount(amount, false),
                incomeToggle.isSelected() ? TransactionType.INCOME : TransactionType.EXPENSE,
                status.getValue(),
                Widgets.selected(category),
                note.getText());
        if (draft.label() == null || draft.label().isBlank()) {
            throw new com.financeapp.core.service.BusinessException("Le libellé est obligatoire");
        }
        return existing == null
                ? ctx.services().transactions().create(draft)
                : ctx.services().transactions().update(existing.id(), draft);
    }
}
