package com.financeapp.desktop.ui.pages;

import com.financeapp.core.categorization.CategorizationRule;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.RuleDialog;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/** Regles de categorisation automatique (locales). */
public final class RulesPage extends Page {

    public RulesPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Règles de catégorisation";
    }

    @Override
    public void refresh() {
        List<CategorizationRule> rules = ctx.services().categorization().findAll();
        Map<Long, String> names = ctx.services().categories().fullNames();

        Button add = new Button("+  Nouvelle règle");
        add.getStyleClass().add("primary");
        add.setOnAction(e -> new RuleDialog(ctx, null).showAndWait().ifPresent(r -> ctx.events().fireChanged()));
        Button apply = new Button("Appliquer aux opérations sans catégorie");
        apply.getStyleClass().add("secondary");
        apply.setOnAction(e -> run(() -> {
            int n = ctx.services().categorization().applyToUncategorized();
            Dialogs.info(window(), "Règles appliquées", n == 0 ? "Aucune opération sans catégorie ne correspond aux règles."
                    : n + " opération(s) ont été catégorisées. Les opérations déjà catégorisées n'ont pas été modifiées.");
            ctx.events().fireChanged();
        }));

        VBox list = new VBox(2);
        for (CategorizationRule r : rules) {
            Label pattern = Widgets.label("« " + r.pattern() + " »", "op-label");
            String scope = r.appliesTo() == null ? "dépenses et revenus"
                    : r.appliesTo() == TransactionType.EXPENSE ? "dépenses" : "revenus";
            VBox texts = new VBox(1, pattern, Widgets.label("Libellé contenant ce texte · " + scope, "op-detail"));
            Label target = Widgets.label("→  " + names.getOrDefault(r.categoryId(), "?"), "op-label");
            HBox row = new HBox(12, texts, Widgets.spacer(), target);
            if (!r.active()) {
                row.getChildren().add(Widgets.badge("inactive", "neutral"));
            }
            row.getChildren().addAll(
                    small("Modifier", () -> new RuleDialog(ctx, r).showAndWait().ifPresent(x -> ctx.events().fireChanged())),
                    small("Supprimer", () -> {
                        if (Dialogs.confirm(window(), "Supprimer la règle", "Supprimer la règle « " + r.pattern()
                                + " » ? Les opérations déjà catégorisées ne changent pas.", "Supprimer")) {
                            ctx.services().categorization().delete(r.id());
                            ctx.events().fireChanged();
                        }
                    }));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("op-row");
            list.getChildren().add(row);
        }
        if (rules.isEmpty()) {
            list.getChildren().add(Widgets.emptyState("Aucune règle. Exemple : « TOTAL » → Transport › Carburant. "
                    + "Des règles vous sont aussi proposées quand vous corrigez une catégorie."));
        }
        Label how = Widgets.label("Les règles s'appliquent aux opérations importées. Sans règle, l'application propose "
                + "la catégorie que vous utilisez habituellement pour le même libellé. Tout reste sur cet ordinateur.", "muted");
        how.setWrapText(true);
        content.getChildren().setAll(Widgets.row(add, apply), Widgets.section(null, list), how);
    }

    private Button small(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("ghost", "compact");
        b.setOnAction(e -> run(action));
        return b;
    }

    private void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            Dialogs.error(window(), ex);
        }
    }
}
