package com.financeapp.desktop.ui.pages;

import com.financeapp.core.budget.BudgetProgress;
import com.financeapp.core.budget.BudgetStatus;
import com.financeapp.core.money.Money;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.MonthPicker;
import com.financeapp.desktop.ui.common.Progress;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.BudgetDialog;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.YearMonth;
import java.util.List;

/** Budgets mensuels par categorie : consommation, reste, alertes. */
public final class BudgetsPage extends Page {

    private final MonthPicker picker;
    private final VBox body = new VBox(14);

    public BudgetsPage(UiContext ctx) {
        super(ctx);
        YearMonth current = YearMonth.from(ctx.services().planning().today());
        picker = new MonthPicker(current, current);
        picker.monthProperty().addListener((o, old, m) -> refresh());
        Button add = new Button("+  Nouveau budget");
        add.getStyleClass().add("primary");
        add.setOnAction(e -> new BudgetDialog(ctx, null).showAndWait().ifPresent(b -> ctx.events().fireChanged()));
        content.getChildren().setAll(Widgets.row(add, Widgets.spacer(), picker), body);
    }

    @Override
    public String title() {
        return "Budgets";
    }

    @Override
    public void refresh() {
        YearMonth month = picker.month();
        UiAsync.load(() -> ctx.services().budgets().progress(month), list -> render(list, month));
    }

    /** Carte d'un budget, reutilisee par le tableau de bord. */
    public static VBox budgetCard(BudgetProgress p, Formats f, boolean compact) {
        Label name = Widgets.label(p.categoryName(), compact ? "op-label" : "account-name");
        Label badge = Widgets.badge(p.status().symbol() + "  " + p.status().label(), switch (p.status()) {
            case OK -> "success";
            case WARNING -> "warning";
            case REACHED -> "info";
            case EXCEEDED -> "danger-small";
        });
        Label amounts = Widgets.label(f.money(p.spent()) + " / " + f.money(p.budget().limit()), "op-detail");
        String rest = p.remaining().isNegative()
                ? f.money(p.remaining().negate()) + " de dépassement"
                : f.money(p.remaining()) + " restants";
        Label remaining = Widgets.label(rest, p.remaining().isNegative() ? "amount-negative" : "op-label");
        Label percent = Widgets.label(Formats.percent(p.percent()), "budget-percent");
        double ratio = p.percent().doubleValue() / 100.0;
        String state = switch (p.status()) {
            case OK -> "progress-ok";
            case WARNING -> "progress-warning";
            case REACHED, EXCEEDED -> "progress-danger";
        };
        VBox card = new VBox(6,
                Widgets.row(name, Widgets.spacer(), badge),
                Widgets.row(Progress.bar(ratio, state), percent),
                Widgets.row(amounts, Widgets.spacer(), remaining));
        if (!compact && p.planned().isPositive()) {
            card.getChildren().add(Widgets.label("dont " + f.money(p.planned()) + " encore prévus ce mois-ci "
                    + "(" + f.money(p.remaining().minus(p.planned())) + " disponibles après ces dépenses)", "op-detail"));
        }
        card.getStyleClass().add(compact ? "budget-compact" : "card");
        return card;
    }

    private void render(List<BudgetProgress> list, YearMonth month) {
        Formats f = ctx.formats();
        body.getChildren().clear();
        if (list.isEmpty()) {
            body.getChildren().add(Widgets.section(null, Widgets.emptyState(
                    "Aucun budget. Fixez un plafond mensuel par catégorie (courses, carburant, loisirs…) : "
                            + "l'application suit la consommation et réserve le reste dans votre disponible réel.")));
            return;
        }
        Money zero = Money.zero(ctx.services().settings().baseCurrency());
        Money limit = list.stream().map(p -> p.budget().limit()).reduce(zero, Money::plus);
        Money spent = list.stream().map(BudgetProgress::spent).reduce(zero, Money::plus);
        long alerts = list.stream().filter(p -> p.status() != BudgetStatus.OK).count();
        body.getChildren().add(new HBox(14,
                Widgets.kpiCard("Budgété", f.money(limit), null, MonthPicker.format(month)),
                Widgets.kpiCard("Dépensé", f.money(spent), null, null),
                Widgets.kpiCard("Reste", f.money(limit.minus(spent)), limit.minus(spent).isNegative() ? "amount-negative" : null, null),
                Widgets.kpiCard("Alertes", Long.toString(alerts), alerts > 0 ? "amount-negative" : null,
                        alerts == 0 ? "tous les budgets sont respectés" : "proches de la limite ou dépassés")));
        for (BudgetProgress p : list) {
            VBox card = budgetCard(p, f, false);
            Button edit = small("Modifier", () -> new BudgetDialog(ctx, p.budget()).showAndWait()
                    .ifPresent(b -> ctx.events().fireChanged()));
            Button delete = small("Supprimer", () -> {
                if (Dialogs.confirm(window(), "Supprimer le budget", "Supprimer le budget « " + p.categoryName()
                        + " » ? Les opérations ne sont pas touchées.", "Supprimer")) {
                    ctx.services().budgets().delete(p.budget().id());
                    ctx.events().fireChanged();
                }
            });
            card.getChildren().add(Widgets.row(Widgets.label(p.budget().reserveInAvailable()
                    ? "Reste réservé dans le disponible réel" : "Non réservé dans le disponible", "op-detail"),
                    Widgets.spacer(), edit, delete));
            body.getChildren().add(card);
        }
    }

    private Button small(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("ghost", "compact");
        b.setOnAction(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                Dialogs.error(window(), ex);
            }
        });
        return b;
    }
}
