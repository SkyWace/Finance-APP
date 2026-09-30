package com.financeapp.desktop.ui.pages;

import com.financeapp.core.loan.Loan;
import com.financeapp.core.loan.LoanStatus;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.Progress;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.AmortizationDialog;
import com.financeapp.desktop.ui.dialogs.LoanDialog;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Credits : capital restant, mensualite, prochaine echeance, progression, tableau d'amortissement. */
public final class LoansPage extends Page {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Formats.LOCALE);

    public LoansPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Crédits";
    }

    @Override
    public void refresh() {
        Formats f = ctx.formats();
        List<LoanStatus> statuses;
        try {
            statuses = ctx.services().loans().statuses();
        } catch (RuntimeException e) {
            content.getChildren().setAll(Widgets.emptyState(e.getMessage()));
            return;
        }
        Map<Long, RecurringRule> rules = ctx.services().recurring().findAll().stream()
                .collect(Collectors.toMap(RecurringRule::id, r -> r));
        Button add = new Button("+  Nouveau crédit");
        add.getStyleClass().add("primary");
        add.setOnAction(e -> new LoanDialog(ctx, null).showAndWait().ifPresent(l -> ctx.events().fireChanged()));
        content.getChildren().setAll(Widgets.row(add));

        List<LoanStatus> running = statuses.stream().filter(s -> !s.finished()).toList();
        if (!statuses.isEmpty()) {
            Money zero = Money.zero(ctx.services().settings().baseCurrency());
            Money remaining = running.stream().map(LoanStatus::remaining).reduce(zero, Money::plus);
            Money monthly = running.stream().map(s -> s.schedule().paymentWithInsurance()).reduce(zero, Money::plus);
            LocalDate end = running.stream().map(s -> s.schedule().endDate()).max(Comparator.naturalOrder()).orElse(null);
            content.getChildren().add(new HBox(14,
                    Widgets.kpiCard("Capital restant dû", f.money(remaining), null, running.size() + " crédit(s) en cours"),
                    Widgets.kpiCard("Mensualités", f.money(monthly), null, "par mois, assurance comprise"),
                    Widgets.kpiCard("Dernière échéance", end == null ? "—" : Formats.capitalize(MONTH.format(end)), null,
                            "tous crédits confondus")));
        }
        if (statuses.isEmpty()) {
            content.getChildren().add(Widgets.section(null, Widgets.emptyState(
                    "Aucun crédit suivi. Ajoutez un crédit auto, immobilier ou à la consommation : capital restant, "
                            + "prochaine échéance et tableau d'amortissement sont calculés à partir de votre offre de prêt.")));
        }
        for (LoanStatus s : statuses) {
            content.getChildren().add(card(s, f, rules));
        }
        List<Loan> archived = ctx.services().loans().findAll().stream().filter(Loan::archived).toList();
        if (!archived.isEmpty()) {
            VBox list = new VBox(2);
            for (Loan l : archived) {
                HBox row = Widgets.row(Widgets.label(l.name(), "op-label"), Widgets.spacer(),
                        small("Désarchiver", () -> ctx.services().loans().setArchived(l.id(), false)));
                row.getStyleClass().add("op-row");
                list.getChildren().add(row);
            }
            content.getChildren().add(Widgets.section("Crédits archivés", list));
        }
    }

    private VBox card(LoanStatus s, Formats f, Map<Long, RecurringRule> rules) {
        Loan loan = s.loan();
        HBox title = Widgets.row(Widgets.label(loan.name(), "account-name"), Widgets.spacer());
        if (s.finished()) {
            title.getChildren().add(Widgets.badge("✓  Remboursé", "success"));
        }
        if (s.schedule().rateEstimated()) {
            title.getChildren().add(Widgets.badge("taux estimé", "neutral"));
        }
        Label percent = Widgets.label(Formats.percent(s.percentRepaid()) + " remboursé", "budget-percent");
        HBox bar = Widgets.row(Progress.bar(s.percentRepaid().doubleValue() / 100.0,
                s.finished() ? "progress-done" : "progress-ok"), percent);

        GridPane grid = new GridPane();
        grid.setHgap(36);
        grid.setVgap(4);
        String paymentText = f.money(s.schedule().payment())
                + (loan.monthlyInsurance().isZero() ? "" : " + " + f.money(loan.monthlyInsurance()) + " assur.");
        String next = s.next().map(r -> Formats.dayMonth(r.date()) + (r.date().getYear() != ctx.services().planning().today().getYear()
                ? " " + r.date().getYear() : "")).orElse("—");
        String[][] cells = {
                {"Capital restant", f.money(s.remaining())},
                {"Mensualité", paymentText},
                {"Prochaine échéance", next},
                {"Fin", Formats.capitalize(MONTH.format(s.schedule().endDate())) + " · " + s.remainingPayments()
                        + " mensualité(s) restante(s)"},
        };
        for (int i = 0; i < cells.length; i++) {
            grid.add(Widgets.label(cells[i][0], "kpi-title"), i, 0);
            grid.add(Widgets.label(cells[i][1], i == 0 ? "loan-value" : "op-label"), i, 1);
        }
        Label details = Widgets.label("Emprunté " + f.money(loan.principal()) + " · taux " + Formats.percent(s.schedule().annualRate())
                + " · " + loan.termMonths() + " mois · coût du crédit " + f.money(s.schedule().totalCost()), "op-detail");

        RecurringRule rule = loan.recurringId() == null ? null : rules.get(loan.recurringId());
        String tracking = rule == null
                ? "Aucune échéance liée : les mensualités ne sont pas comptées dans le disponible ni les prévisions."
                : "Mensualités prévues via la récurrence « " + rule.label() + " » (" + f.money(rule.amount()) + ").";
        HBox actions = Widgets.row(Widgets.label(tracking, "op-detail"), Widgets.spacer(),
                small("Tableau d'amortissement", () -> new AmortizationDialog(ctx, loan).showAndWait()),
                small("Modifier", () -> new LoanDialog(ctx, loan).showAndWait().ifPresent(x -> ctx.events().fireChanged())),
                small("Archiver", () -> ctx.services().loans().setArchived(loan.id(), true)),
                small("Supprimer", () -> {
                    if (Dialogs.confirm(window(), "Supprimer le crédit", "Supprimer « " + loan.name() + " » ? "
                            + (rule == null ? "" : "La récurrence « " + rule.label() + " » est conservée : "
                            + "arrêtez-la dans Récurrences si les mensualités ne sont plus dues."), "Supprimer")) {
                        ctx.services().loans().delete(loan.id());
                    }
                }));
        VBox card = new VBox(10, title, bar, grid, details, actions);
        card.getStyleClass().add("card");
        return card;
    }

    private Button small(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("ghost", "compact");
        b.setOnAction(e -> {
            try {
                action.run();
                ctx.events().fireChanged();
            } catch (RuntimeException ex) {
                Dialogs.error(window(), ex);
            }
        });
        return b;
    }
}
