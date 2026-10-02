package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.service.BusinessException;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.SplitEditor;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;

import java.time.LocalDate;

/** Creation ou modification d'une operation recurrente. */
public final class RecurringDialog extends FormDialog<RecurringRule> {

    private final RecurringRule existing;
    private final ComboBox<Choice<TransactionType>> type = new ComboBox<>();
    private final ComboBox<Choice<Long>> account;
    private final ComboBox<Choice<Long>> toAccount;
    private final TextField label = new TextField();
    private final TextField amount = new TextField();
    private final ComboBox<Choice<Long>> category = new ComboBox<>();
    private final ComboBox<Frequency> frequency = new ComboBox<>();
    private final Spinner<Integer> interval = new Spinner<>(1, 365, 1);
    private final DatePicker start;
    private final DatePicker end;
    private final CheckBox certain = new CheckBox("Revenu suffisamment certain pour être compté dans le disponible réel");
    private final CheckBox active = new CheckBox("Récurrence active");
    private final TextField note = new TextField();
    private final javafx.scene.control.Button splitButton = new javafx.scene.control.Button("Ventiler…");
    private final javafx.scene.layout.HBox categoryBox = new javafx.scene.layout.HBox(8);
    private final SplitEditor splitEditor;

    /** @param existing regle a modifier, ou modele pre-rempli sans identifiant (creation), ou {@code null} */
    public RecurringDialog(UiContext ctx, RecurringRule existing) {
        super(ctx, existing == null || existing.id() == null ? "Nouvelle opération récurrente" : "Modifier la récurrence",
                "Enregistrer");
        this.existing = existing;
        var accounts = ctx.services().accounts().findAll();
        splitEditor = new SplitEditor(keep -> Widgets.categoryChoices(ctx.services().categories().activeTree(),
                Widgets.selected(type) == TransactionType.INCOME, keep), amount::getText, this::fitToContent, this::stopSplit);
        splitButton.getStyleClass().addAll("ghost", "compact");
        splitButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        splitButton.setTooltip(new javafx.scene.control.Tooltip(
                "Répartir le montant sur plusieurs catégories (ex. loyer 700 € + charges 100 €)"));
        splitButton.setOnAction(e -> startSplit());
        javafx.scene.layout.HBox.setHgrow(category, javafx.scene.layout.Priority.ALWAYS);
        categoryBox.getChildren().setAll(category, splitButton);
        amount.textProperty().addListener((o, a, b) -> splitEditor.totalChanged());

        type.getItems().setAll(
                new Choice<>(TransactionType.EXPENSE, "Dépense"),
                new Choice<>(TransactionType.INCOME, "Revenu"),
                new Choice<>(TransactionType.TRANSFER, "Virement interne (ex. épargne)"));
        type.setMaxWidth(Double.MAX_VALUE);
        account = Widgets.accountCombo(accounts, existing == null ? null : existing.accountId());
        toAccount = Widgets.accountCombo(accounts, existing == null ? null : existing.toAccountId());
        frequency.getItems().setAll(Frequency.values());
        frequency.setMaxWidth(Double.MAX_VALUE);
        category.setMaxWidth(Double.MAX_VALUE);
        interval.setEditable(true);
        start = Widgets.datePicker(existing == null ? ctx.services().planning().today() : existing.startDate());
        end = Widgets.datePicker(existing == null ? null : existing.endDate());
        end.setPromptText("Sans fin");
        label.setPromptText("Ex. Loyer, Salaire, Netflix");
        amount.setPromptText("0,00");

        if (existing == null) {
            Widgets.select(type, TransactionType.EXPENSE);
            frequency.setValue(Frequency.MONTHLY);
            certain.setSelected(true);
            active.setSelected(true);
            if (toAccount.getItems().size() > 1) {
                toAccount.getSelectionModel().select(1);
            }
        } else {
            Widgets.select(type, existing.type());
            label.setText(existing.label());
            amount.setText(AmountParser.toEditable(existing.amount().amount()));
            frequency.setValue(existing.frequency());
            interval.getValueFactory().setValue(existing.interval());
            certain.setSelected(existing.certain());
            active.setSelected(existing.active());
            note.setText(existing.note());
        }

        type.valueProperty().addListener((o, old, t) -> updateVisibility());
        frequency.valueProperty().addListener((o, old, f) -> updateVisibility());

        addRow("Type", type);
        addRow("Compte", account);
        addOptionalRow("Vers le compte", toAccount);
        addRow("Libellé", label);
        addRow("Montant", amount);
        addOptionalRow("Catégorie", categoryBox);
        addFullRow(splitEditor.node());
        addRow("Fréquence", frequency);
        addOptionalRow("Intervalle (N)", interval);
        addRow("Première échéance", start);
        addRow("Dernière échéance", end);
        addRow("Commentaire", note);
        addFullRow(certain);
        addFullRow(active);
        updateVisibility();
        if (existing != null) {
            Widgets.select(category, existing.categoryId());
            if (existing.isSplit()) {
                showSplit(existing.splits().stream()
                        .map(l -> new SplitEditor.Line(l.categoryId(), l.amount().amount())).toList());
            }
        }
        setOnShown(e -> label.requestFocus());
    }

    private void updateVisibility() {
        TransactionType t = Widgets.selected(type);
        boolean transfer = t == TransactionType.TRANSFER;
        toAccount.setVisible(transfer);
        if (transfer && splitEditor.isActive()) {
            stopSplit(); // un virement ne se ventile pas
        }
        categoryBox.setVisible(!transfer);
        certain.setVisible(t == TransactionType.INCOME);
        certain.setManaged(t == TransactionType.INCOME);
        interval.setVisible(frequency.getValue() != null && frequency.getValue().isCustom());
        Long current = Widgets.selected(category);
        category.getItems().setAll(Widgets.categoryChoices(ctx.services().categories().activeTree(),
                t == TransactionType.INCOME, existing == null ? null : existing.categoryId()));
        Widgets.select(category, current);
        if (category.getValue() == null) {
            category.getSelectionModel().selectFirst();
        }
        splitEditor.refreshChoices();
    }

    private void startSplit() {
        java.math.BigDecimal total = AmountParser.parse(amount.getText()).map(java.math.BigDecimal::abs).orElse(null);
        showSplit(java.util.List.of(new SplitEditor.Line(Widgets.selected(category), total), new SplitEditor.Line(null, null)));
    }

    private void showSplit(java.util.List<SplitEditor.Line> lines) {
        category.setVisible(false);
        category.setManaged(false);
        splitButton.setVisible(false);
        splitButton.setManaged(false);
        splitEditor.open(lines);
    }

    private void stopSplit() {
        Long first = splitEditor.close();
        category.setVisible(true);
        category.setManaged(true);
        splitButton.setVisible(true);
        splitButton.setManaged(true);
        Widgets.select(category, first);
    }

    @Override
    protected RecurringRule submit() {
        TransactionType t = require(Widgets.selected(type), "Choisissez un type");
        long accountId = require(Widgets.selected(account), "Choisissez un compte");
        var acc = ctx.services().accounts().get(accountId);
        LocalDate startDate = require(Widgets.dateValue(start), "Date de première échéance invalide");
        LocalDate endDate = end.getEditor().getText().isBlank() ? null : Widgets.dateValue(end);
        if (!end.getEditor().getText().isBlank() && endDate == null) {
            throw new BusinessException("Date de dernière échéance invalide");
        }
        if (label.getText().isBlank()) {
            throw new BusinessException("Le libellé est obligatoire");
        }
        Money total = Money.of(requireAmount(amount, false), acc.currency());
        java.util.List<com.financeapp.core.transaction.SplitLine> splits = java.util.List.of();
        if (t != TransactionType.TRANSFER && splitEditor.isActive()) {
            splits = splitEditor.lines().stream()
                    .map(l -> new com.financeapp.core.transaction.SplitLine(l.categoryId(), Money.of(l.amount(), acc.currency())))
                    .toList();
            Money sum = splits.stream().map(com.financeapp.core.transaction.SplitLine::amount)
                    .reduce(Money.zero(acc.currency()), Money::plus);
            if (!sum.equals(total)) {
                Money gap = total.minus(sum);
                throw new BusinessException("La ventilation (" + AmountParser.toEditable(sum.amount()) + ") ne correspond pas "
                        + "au montant (" + AmountParser.toEditable(total.amount()) + ") : "
                        + (gap.isPositive() ? "reste " + AmountParser.toEditable(gap.amount()) + " à répartir"
                                            : "dépassement de " + AmountParser.toEditable(gap.negate().amount())));
            }
        }
        RecurringRule rule = new RecurringRule(
                existing == null ? null : existing.id(),
                accountId,
                t == TransactionType.TRANSFER ? require(Widgets.selected(toAccount), "Choisissez le compte destinataire") : null,
                t,
                label.getText(),
                total,
                t == TransactionType.TRANSFER || !splits.isEmpty() ? null : Widgets.selected(category),
                require(frequency.getValue(), "Choisissez une fréquence"),
                interval.getValue() == null ? 1 : interval.getValue(),
                startDate,
                endDate,
                existing == null ? null : existing.trackedFrom(),
                t != TransactionType.INCOME || certain.isSelected(),
                active.isSelected(),
                note.getText() == null || note.getText().isBlank() ? null : note.getText().strip(),
                splits);
        return ctx.services().recurring().save(rule);
    }
}
