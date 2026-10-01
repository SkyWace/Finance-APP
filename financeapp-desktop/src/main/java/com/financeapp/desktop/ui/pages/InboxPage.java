package com.financeapp.desktop.ui.pages;

import com.financeapp.core.account.Account;
import com.financeapp.core.service.InboxService;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.ImportWizard;
import com.financeapp.desktop.ui.dialogs.RuleProposal;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Operations importees a valider : la categorie proposee est pre-remplie ;
 * l'utilisateur valide ou corrige. Une correction propose de creer une regle.
 */
public final class InboxPage extends Page {

    public InboxPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "À valider";
    }

    @Override
    public void refresh() {
        UiAsync.load(() -> ctx.services().inbox().items(), this::render);
    }

    private void render(List<InboxService.InboxItem> items) {
        Formats f = ctx.formats();
        Map<Long, Account> accounts = ctx.services().accounts().findAll().stream()
                .collect(Collectors.toMap(Account::id, a -> a));
        List<Choice<Long>> expenseChoices = Widgets.categoryChoices(ctx.services().categories().activeTree(), false, null);
        List<Choice<Long>> incomeChoices = Widgets.categoryChoices(ctx.services().categories().activeTree(), true, null);

        Button importButton = new Button("Importer un relevé…");
        importButton.getStyleClass().add("primary");
        importButton.setOnAction(e -> new ImportWizard(ctx).showAndWait().ifPresent(b -> ctx.events().fireChanged()));
        long withCategory = items.stream().filter(i -> i.proposedCategoryId() != null).count();
        Button validateAll = new Button("Tout valider (" + withCategory + " avec catégorie)");
        validateAll.getStyleClass().add("secondary");
        validateAll.setDisable(withCategory == 0);
        validateAll.setOnAction(e -> run(() -> {
            ctx.services().inbox().validateAllWithCategory();
            ctx.events().fireChanged();
        }));
        content.getChildren().setAll(Widgets.row(importButton, validateAll));

        if (items.isEmpty()) {
            content.getChildren().add(Widgets.section(null, Widgets.emptyState(
                    "Rien à valider. Les opérations importées depuis un relevé bancaire arrivent ici avec une "
                            + "catégorie proposée, pour que vous la confirmiez ou la corrigiez.")));
            return;
        }
        Label intro = Widgets.label(items.size() + " opération(s) importée(s) à valider. Elles comptent déjà dans vos "
                + "soldes ; seule leur catégorie reste à confirmer.", "muted");
        VBox list = new VBox(2);
        for (InboxService.InboxItem item : items) {
            Transaction t = item.transaction();
            ComboBox<Choice<Long>> category = new ComboBox<>();
            category.getItems().setAll(t.amount().isNegative() ? expenseChoices : incomeChoices);
            Widgets.select(category, item.proposedCategoryId());
            if (category.getValue() == null) {
                category.getSelectionModel().selectFirst();
            }
            category.setPrefWidth(240);
            if (t.isSplit()) { // deja ventilee : validee telle quelle
                category.setDisable(true);
                category.setPromptText("Ventilée");
                category.getSelectionModel().clearSelection();
            }
            String origin = t.isSplit() ? "ventilée sur " + t.splits().size() + " catégories"
                    : t.categoryId() != null ? "catégorie appliquée à l'import"
                    : item.suggestion() == null ? "aucune suggestion"
                    : "suggestion : " + item.suggestion().source().label()
                      + (item.suggestion().rule() != null ? " « " + item.suggestion().rule().pattern() + " »" : "");
            Label date = Widgets.label(Formats.shortDate(t.date()), "op-date");
            date.setMinWidth(48);
            VBox texts = new VBox(1, Widgets.label(t.label(), "op-label"), Widgets.label(
                    (accounts.containsKey(t.accountId()) ? accounts.get(t.accountId()).name() : "") + " · " + origin, "op-detail"));
            Label amount = Widgets.amount(f, t.amount());
            amount.setMinWidth(110);
            amount.setAlignment(Pos.CENTER_RIGHT);
            Button validate = new Button("Valider");
            validate.getStyleClass().addAll("secondary", "compact");
            validate.setOnAction(e -> run(() -> {
                Long chosen = Widgets.selected(category);
                ctx.services().inbox().validate(t.id(), chosen);
                boolean corrected = !Objects.equals(chosen, item.proposedCategoryId());
                if (!t.isSplit() && (corrected || item.suggestion() == null || item.suggestion().rule() == null)) {
                    RuleProposal.offer(ctx, t.label(), t.type(), chosen);
                }
                ctx.events().fireChanged();
            }));
            HBox row = new HBox(12, date, texts, Widgets.spacer(), amount, category, validate);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("op-row");
            list.getChildren().add(row);
        }
        content.getChildren().addAll(intro, Widgets.section(null, list));
    }

    private void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            Dialogs.error(window(), ex);
        }
    }
}
