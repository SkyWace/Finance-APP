package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.categorization.CategorizationRule;
import com.financeapp.core.service.BusinessException;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

/** Creation ou modification d'une regle de categorisation automatique. */
public final class RuleDialog extends FormDialog<CategorizationRule> {

    private final CategorizationRule existing;
    private final TextField pattern = new TextField();
    private final ComboBox<Choice<TransactionType>> type = new ComboBox<>();
    private final ComboBox<Choice<Long>> category = new ComboBox<>();
    private final CheckBox active = new CheckBox("Règle active");

    /**
     * @param existing regle a modifier, modele pre-rempli sans identifiant, ou {@code null}
     */
    public RuleDialog(UiContext ctx, CategorizationRule existing) {
        super(ctx, existing == null || existing.id() == null ? "Nouvelle règle de catégorisation" : "Modifier la règle",
                "Enregistrer");
        this.existing = existing;
        type.getItems().setAll(new Choice<>(TransactionType.EXPENSE, "Dépenses uniquement"),
                new Choice<>(TransactionType.INCOME, "Revenus uniquement"),
                new Choice<>(null, "Dépenses et revenus"));
        type.setMaxWidth(Double.MAX_VALUE);
        category.setMaxWidth(Double.MAX_VALUE);
        pattern.setPromptText("Ex. CARREFOUR, TOTAL, NETFLIX");
        if (existing == null) {
            Widgets.select(type, TransactionType.EXPENSE);
            active.setSelected(true);
        } else {
            pattern.setText(existing.pattern());
            Widgets.select(type, existing.appliesTo());
            active.setSelected(existing.active());
        }
        type.valueProperty().addListener((o, old, t) -> refreshCategories());
        refreshCategories();
        if (existing != null) {
            Widgets.select(category, existing.categoryId());
        }
        addRow("Si le libellé contient", pattern);
        addRow("Pour", type);
        addRow("Classer dans", category);
        addFullRow(active);
        Label hint = Widgets.label("La comparaison ignore majuscules, accents, chiffres et préfixes bancaires (« CB », "
                + "« PRLV SEPA »…) et porte sur des mots entiers : « TOTAL » correspond à « CB STATION TOTAL 25/09 » "
                + "mais pas à « TOTALEMENT ». Le motif le plus précis l'emporte.", "hint");
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        addFullRow(hint);
        setOnShown(e -> pattern.requestFocus());
    }

    private void refreshCategories() {
        Long current = Widgets.selected(category);
        category.getItems().setAll(Widgets.categoryChoices(ctx.services().categories().activeTree(),
                Widgets.selected(type) == TransactionType.INCOME, existing == null ? null : existing.categoryId()));
        category.getItems().removeIf(c -> c.value() == null);
        Widgets.select(category, current);
        if (category.getValue() == null) {
            category.getSelectionModel().selectFirst();
        }
    }

    @Override
    protected CategorizationRule submit() {
        if (pattern.getText().isBlank()) {
            throw new BusinessException("Saisissez le texte à rechercher dans le libellé");
        }
        CategorizationRule rule = new CategorizationRule(existing == null ? null : existing.id(), pattern.getText(),
                require(Widgets.selected(category), "Choisissez une catégorie"), Widgets.selected(type), active.isSelected());
        return ctx.services().categorization().save(rule);
    }
}
