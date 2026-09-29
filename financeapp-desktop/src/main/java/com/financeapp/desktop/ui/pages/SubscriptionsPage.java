package com.financeapp.desktop.ui.pages;

import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.service.SubscriptionService;
import com.financeapp.core.subscription.RecurringPaymentCandidate;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.RecurringDialog;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.List;

/**
 * Abonnements : cout mensuel ET annuel, pour rendre visibles les petites
 * depenses recurrentes, et paiements reguliers reperes dans l'historique.
 */
public final class SubscriptionsPage extends Page {

    private record Data(SubscriptionService.Overview overview, List<RecurringPaymentCandidate> candidates) {
    }

    public SubscriptionsPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Abonnements";
    }

    @Override
    public void refresh() {
        var service = ctx.services().subscriptions();
        UiAsync.load(() -> new Data(service.overview(), service.detectCandidates()), this::render);
    }

    private void render(Data data) {
        Formats f = ctx.formats();
        var o = data.overview();
        HBox kpis = new HBox(14,
                Widgets.kpiCard("Abonnements", Integer.toString(o.subscriptions().size()), null, "actifs"),
                Widgets.kpiCard("Coût mensuel", f.money(o.monthlyTotal()), null, "équivalent par mois"),
                Widgets.kpiCard("Coût annuel", f.money(o.yearlyTotal()), "accent", "ce que ces abonnements coûtent sur un an"));

        VBox list = new VBox(2);
        for (RecurringRule r : o.subscriptions()) {
            var monthly = r.monthlyEquivalent().negate();
            Label name = Widgets.label(r.label(), "op-label");
            String next = ctx.services().recurring().nextOccurrence(r).map(d -> " · prochain : " + Formats.date(d)).orElse("");
            VBox texts = new VBox(1, name, Widgets.label(frequency(r) + next, "op-detail"));
            Label perMonth = Widgets.label(f.money(monthly) + " /mois", "op-label");
            Label perYear = Widgets.label(f.money(monthly.multiply(BigDecimal.valueOf(12))) + " /an", "op-detail");
            VBox amounts = new VBox(1, perMonth, perYear);
            amounts.setAlignment(Pos.CENTER_RIGHT);
            HBox row = new HBox(12, texts, Widgets.spacer(), amounts);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("op-row");
            list.getChildren().add(row);
        }
        if (o.subscriptions().isEmpty()) {
            list.getChildren().add(Widgets.emptyState("Aucun abonnement. Les abonnements sont les opérations récurrentes "
                    + "classées dans la catégorie « Abonnements » (streaming, téléphone, sport…)."));
        }
        if (!o.subscriptions().isEmpty()) {
            HBox total = Widgets.row(Widgets.label("TOTAL", "total-label"), Widgets.spacer(),
                    Widgets.label(f.money(o.monthlyTotal()) + " /mois · " + f.money(o.yearlyTotal()) + " /an", "total-value"));
            total.getStyleClass().add("total-row");
            list.getChildren().add(total);
        }

        VBox detected = new VBox(2);
        for (RecurringPaymentCandidate c : data.candidates()) {
            Label name = Widgets.label(c.label(), "op-label");
            String info = c.frequency().label() + " · " + c.occurrences() + " paiements repérés · dernier le "
                    + Formats.date(c.lastDate()) + " · prochain attendu vers le " + Formats.date(c.nextExpected());
            VBox texts = new VBox(1, name, Widgets.label(info, "op-detail"));
            Label amount = Widgets.label(f.money(c.typicalAmount()) + " · ≈ " + f.money(c.monthlyCost()) + " /mois", "op-label");
            Button register = new Button("Enregistrer");
            register.getStyleClass().addAll("secondary", "compact");
            register.setOnAction(e -> register(c));
            Button dismiss = new Button("Ignorer");
            dismiss.getStyleClass().addAll("ghost", "compact");
            dismiss.setOnAction(e -> {
                ctx.services().subscriptions().dismiss(c);
                ctx.events().fireChanged();
            });
            HBox row = new HBox(12, texts, Widgets.spacer(), amount, register, dismiss);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("op-row");
            detected.getChildren().add(row);
        }
        if (data.candidates().isEmpty()) {
            detected.getChildren().add(Widgets.emptyState("Aucun paiement régulier non enregistré n'a été repéré dans vos opérations."));
        }
        Label how = Widgets.label("Détection locale : même libellé, montant stable (±15 %) et intervalle régulier "
                + "(hebdomadaire à annuel). Rien n'est enregistré sans votre accord.", "muted");
        how.setWrapText(true);

        content.getChildren().setAll(kpis, Widgets.section("Abonnements enregistrés", list),
                Widgets.section("Paiements réguliers détectés", detected, how));
    }

    private static String frequency(RecurringRule r) {
        return r.frequency().isCustom() ? r.frequency().label().replace("N", Integer.toString(r.interval())) : r.frequency().label();
    }

    /** Ouvre le formulaire de recurrence pre-rempli ; l'utilisateur valide ou ajuste. */
    private void register(RecurringPaymentCandidate c) {
        Long category = c.categoryId() != null ? c.categoryId()
                : ctx.services().categories().findBySystemCode(SubscriptionService.SUBSCRIPTIONS_CODE).map(x -> x.id()).orElse(null);
        RecurringRule template = new RecurringRule(null, c.accountId(), null, TransactionType.EXPENSE, c.label(),
                c.typicalAmount(), category, c.frequency(), 1, c.nextExpected(), null, null, true, true, null);
        new RecurringDialog(ctx, template).showAndWait().ifPresent(r -> ctx.events().fireChanged());
    }
}
