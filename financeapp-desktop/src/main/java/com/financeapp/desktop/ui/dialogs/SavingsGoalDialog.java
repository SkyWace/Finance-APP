package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.account.Account;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.BusinessException;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Creation ou modification d'un objectif d'epargne. */
public final class SavingsGoalDialog extends FormDialog<SavingsGoal> {

    private final SavingsGoal existing;
    private final TextField name = new TextField();
    private final TextField target = new TextField();
    private final DatePicker date;
    private final ComboBox<Choice<Long>> account = new ComboBox<>();
    private final TextField saved = new TextField();
    private final CheckBox reserve = new CheckBox("Réserver chaque mois l'épargne nécessaire dans le disponible réel");

    public SavingsGoalDialog(UiContext ctx, SavingsGoal existing) {
        super(ctx, existing == null ? "Nouvel objectif d'épargne" : "Modifier l'objectif", "Enregistrer");
        this.existing = existing;
        date = Widgets.datePicker(existing == null ? null : existing.targetDate());
        date.setPromptText("Sans échéance");
        account.getItems().add(new Choice<>(null, "— Suivi manuel du montant épargné —"));
        for (Account a : ctx.services().accounts().findActive()) {
            account.getItems().add(new Choice<>(a.id(), "Solde du compte « " + a.name() + " »"));
        }
        account.setMaxWidth(Double.MAX_VALUE);
        name.setPromptText("Ex. Fonds d'urgence");
        target.setPromptText("Ex. 5000");
        saved.setPromptText("0,00");
        if (existing == null) {
            account.getSelectionModel().selectFirst();
        } else {
            name.setText(existing.name());
            target.setText(AmountParser.toEditable(existing.target().amount()));
            Widgets.select(account, existing.linkedAccountId());
            saved.setText(AmountParser.toEditable(existing.manualSaved().amount()));
            reserve.setSelected(existing.reserveInAvailable());
        }
        saved.visibleProperty().bind(account.valueProperty().map(c -> c == null || c.value() == null));

        addRow("Nom", name);
        addRow("Montant visé", target);
        addRow("Échéance", date);
        addRow("Épargne suivie via", account);
        addOptionalRow("Déjà épargné", saved);
        addFullRow(reserve);
        Label hint = Widgets.label("N'activez la réserve que si aucun virement récurrent n'alimente déjà cet objectif : "
                + "sinon la même somme serait déduite deux fois.", "hint");
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        addFullRow(hint);
        setOnShown(e -> name.requestFocus());
    }

    @Override
    protected SavingsGoal submit() {
        var currency = ctx.services().settings().baseCurrency();
        Long accountId = Widgets.selected(account);
        BigDecimal manual = accountId != null || saved.getText().isBlank() ? BigDecimal.ZERO : requireAmount(saved, true);
        if (manual.signum() < 0) {
            throw new BusinessException("Le montant déjà épargné ne peut pas être négatif");
        }
        LocalDate targetDate = date.getEditor().getText().isBlank() ? null : Widgets.dateValue(date);
        if (!date.getEditor().getText().isBlank() && targetDate == null) {
            throw new BusinessException("Date d'échéance invalide");
        }
        if (name.getText().isBlank()) {
            throw new BusinessException("Le nom de l'objectif est obligatoire");
        }
        SavingsGoal goal = new SavingsGoal(
                existing == null ? null : existing.id(),
                name.getText(),
                Money.of(requireAmount(target, false), currency),
                targetDate,
                accountId,
                Money.of(manual, currency),
                reserve.isSelected(),
                existing != null && existing.archived());
        return ctx.services().goals().save(goal);
    }
}
