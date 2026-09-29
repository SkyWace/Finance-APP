package com.financeapp.desktop.ui.pages;

import com.financeapp.core.goal.GoalProgress;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.Progress;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.SavingsGoalDialog;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;

/** Objectifs d'epargne : avancement, reste, effort mensuel necessaire. */
public final class SavingsGoalsPage extends Page {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Formats.LOCALE);

    public SavingsGoalsPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Objectifs d'épargne";
    }

    @Override
    public void refresh() {
        UiAsync.load(() -> ctx.services().goals().progress(), this::render);
    }

    private void render(List<GoalProgress> goals) {
        Formats f = ctx.formats();
        Button add = new Button("+  Nouvel objectif");
        add.getStyleClass().add("primary");
        add.setOnAction(e -> new SavingsGoalDialog(ctx, null).showAndWait().ifPresent(g -> ctx.events().fireChanged()));
        content.getChildren().setAll(Widgets.row(add));
        if (goals.isEmpty()) {
            content.getChildren().add(Widgets.section(null, Widgets.emptyState(
                    "Aucun objectif. Fonds d'urgence, vacances, voiture… : fixez un montant et une échéance, "
                            + "l'application calcule l'épargne mensuelle nécessaire.")));
            return;
        }
        for (GoalProgress p : goals) {
            content.getChildren().add(goalCard(p, f));
        }
    }

    private VBox goalCard(GoalProgress p, Formats f) {
        SavingsGoal g = p.goal();
        HBox title = Widgets.row(Widgets.label(g.name(), "account-name"), Widgets.spacer());
        if (p.reached()) {
            title.getChildren().add(Widgets.badge("✓  Objectif atteint", "success"));
        } else if (p.overdue()) {
            title.getChildren().add(Widgets.badge("!  Échéance dépassée", "warning"));
        }
        Label percent = Widgets.label(Formats.percent(p.percent()), "budget-percent");
        HBox bar = Widgets.row(Progress.bar(p.percent().doubleValue() / 100.0, p.reached() ? "progress-done" : "progress-ok"), percent);
        Label amounts = Widgets.label(f.money(p.saved()) + " / " + f.money(g.target()), "op-label");
        Label remaining = Widgets.label(p.reached() ? "" : "Reste " + f.money(p.remaining()), "op-label");

        String schedule;
        if (g.targetDate() == null) {
            schedule = "Sans échéance";
        } else {
            schedule = "Échéance : " + MONTH.format(g.targetDate());
            if (p.monthlyNeeded() != null) {
                schedule += " · " + f.money(p.monthlyNeeded()) + " par mois nécessaires"
                        + (p.monthsLeft() != null && p.monthsLeft() > 0 ? " (" + p.monthsLeft() + " mois)" : "");
            }
        }
        String source = g.linkedAccountId() == null ? "Montant suivi manuellement"
                : "Suit le solde du compte « " + ctx.services().accounts().get(g.linkedAccountId()).name() + " »";
        if (g.reserveInAvailable()) {
            source += " · effort mensuel réservé dans le disponible";
        }

        HBox actions = Widgets.row(Widgets.label(source, "op-detail"), Widgets.spacer());
        if (g.linkedAccountId() == null) {
            actions.getChildren().add(small("Verser / retirer…", () -> contribute(g)));
        }
        actions.getChildren().addAll(
                small("Modifier", () -> new SavingsGoalDialog(ctx, g).showAndWait().ifPresent(x -> ctx.events().fireChanged())),
                small("Archiver", () -> {
                    ctx.services().goals().save(g.withArchived(true));
                    ctx.events().fireChanged();
                }),
                small("Supprimer", () -> {
                    if (Dialogs.confirm(window(), "Supprimer l'objectif", "Supprimer « " + g.name() + " » ?", "Supprimer")) {
                        ctx.services().goals().delete(g.id());
                        ctx.events().fireChanged();
                    }
                }));
        VBox card = new VBox(8, title, bar, Widgets.row(amounts, Widgets.spacer(), remaining),
                Widgets.label(schedule, "op-detail"), actions);
        card.getStyleClass().add("card");
        return card;
    }

    private void contribute(SavingsGoal g) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Verser ou retirer");
        dialog.setHeaderText(null);
        dialog.setContentText("Montant (négatif pour un retrait) :");
        Dialogs.style(dialog.getDialogPane(), window(), dialog);
        dialog.showAndWait().ifPresent(text -> AmountParser.parse(text).ifPresentOrElse(amount -> {
            ctx.services().goals().addContribution(g.id(), amount);
            ctx.events().fireChanged();
        }, () -> Dialogs.error(window(), new IllegalArgumentException("Montant invalide : saisissez par exemple 150 ou -50"))));
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
