package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.budget.Budget;
import com.financeapp.core.money.Money;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/** Creation ou modification d'un budget mensuel. */
public final class BudgetDialog extends FormDialog<Budget> {

    private final Budget existing;
    private final ComboBox<Choice<Long>> category = new ComboBox<>();
    private final TextField amount = new TextField();
    private final CheckBox reserve = new CheckBox("Réserver le reste de ce budget dans le disponible réel");

    public BudgetDialog(UiContext ctx, Budget existing) {
        super(ctx, existing == null ? "Nouveau budget mensuel" : "Modifier le budget", "Enregistrer");
        this.existing = existing;
        category.getItems().setAll(Widgets.categoryChoices(ctx.services().categories().activeTree(), false,
                existing == null ? null : existing.categoryId()));
        category.getItems().removeIf(c -> c.value() == null);
        category.setMaxWidth(Double.MAX_VALUE);
        amount.setPromptText("Ex. 300");
        if (existing == null) {
            category.getSelectionModel().selectFirst();
            reserve.setSelected(true);
        } else {
            Widgets.select(category, existing.categoryId());
            amount.setText(AmountParser.toEditable(existing.limit().amount()));
            reserve.setSelected(existing.reserveInAvailable());
        }
        addRow("Catégorie", category);
        addRow("Montant par mois", amount);
        addFullRow(reserve);
        Label hint = Widgets.label("Le budget d'une catégorie couvre aussi ses sous-catégories. Les dépenses déjà prévues "
                + "dans la catégorie sont déduites de la réserve, pour ne jamais être comptées deux fois.", "hint");
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        addFullRow(hint);
        setOnShown(e -> amount.requestFocus());
    }

    @Override
    protected Budget submit() {
        Budget budget = new Budget(
                existing == null ? null : existing.id(),
                require(Widgets.selected(category), "Choisissez une catégorie"),
                Money.of(requireAmount(amount, false), ctx.services().settings().baseCurrency()),
                reserve.isSelected(),
                true);
        return ctx.services().budgets().save(budget);
    }
}
