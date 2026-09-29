package com.financeapp.desktop.ui.pages;

import com.financeapp.core.account.Account;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.RecurringDialog;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Operations recurrentes, avec leur equivalent mensuel et annuel. */
public final class RecurringPage extends Page {

    public RecurringPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Opérations récurrentes";
    }

    @Override
    public void refresh() {
        Formats f = ctx.formats();
        LocalDate today = ctx.services().planning().today();
        List<RecurringRule> rules = ctx.services().recurring().findAll();
        Map<Long, Account> accounts = ctx.services().accounts().findAll().stream()
                .collect(Collectors.toMap(Account::id, a -> a));
        Map<Long, String> categories = ctx.services().categories().fullNames();
        Currency base = ctx.services().settings().baseCurrency();

        Button add = new Button("+  Nouvelle récurrence");
        add.getStyleClass().add("primary");
        add.setOnAction(e -> edit(null));

        Money zero = Money.zero(base);
        Money charges = zero;
        Money incomes = zero;
        VBox active = new VBox(4);
        VBox ended = new VBox(4);
        for (RecurringRule r : rules) {
            boolean isEnded = !r.active() || (r.endDate() != null && r.endDate().isBefore(today));
            (isEnded ? ended : active).getChildren().add(ruleRow(r, accounts, categories, f, isEnded));
            if (!isEnded && r.type() != TransactionType.TRANSFER && r.amount().currency().equals(base)) {
                Money monthly = r.monthlyEquivalent();
                if (monthly.isNegative()) {
                    charges = charges.plus(monthly);
                } else {
                    incomes = incomes.plus(monthly);
                }
            }
        }
        if (active.getChildren().isEmpty()) {
            active.getChildren().add(Widgets.emptyState("Aucune récurrence active. Ajoutez votre salaire, votre loyer, "
                    + "vos abonnements… : ils alimenteront automatiquement le disponible réel et les prévisions."));
        }
        HBox kpis = new HBox(14,
                Widgets.kpiCard("Charges récurrentes", f.signed(charges) + " /mois", Formats.signClass(charges),
                        "soit " + f.signed(charges.multiply(BigDecimal.valueOf(12))) + " par an"),
                Widgets.kpiCard("Revenus récurrents", f.signed(incomes) + " /mois", Formats.signClass(incomes),
                        "soit " + f.signed(incomes.multiply(BigDecimal.valueOf(12))) + " par an"),
                Widgets.kpiCard("Solde récurrent", f.signed(incomes.plus(charges)) + " /mois",
                        Formats.signClass(incomes.plus(charges)), "hors virements internes"));

        content.getChildren().setAll(Widgets.row(add), kpis, Widgets.section("Actives", active));
        if (!ended.getChildren().isEmpty()) {
            content.getChildren().add(Widgets.section("Terminées ou suspendues", ended));
        }
    }

    private HBox ruleRow(RecurringRule r, Map<Long, Account> accounts, Map<Long, String> categories,
                         Formats f, boolean ended) {
        String account = accounts.containsKey(r.accountId()) ? accounts.get(r.accountId()).name() : "?";
        String detail = r.type() == TransactionType.TRANSFER
                ? account + " → " + (accounts.containsKey(r.toAccountId()) ? accounts.get(r.toAccountId()).name() : "?")
                : (r.categoryId() == null ? account : categories.getOrDefault(r.categoryId(), "") + " · " + account);
        String frequency = r.frequency().isCustom()
                ? r.frequency().label().replace("N", Integer.toString(r.interval())) : r.frequency().label();
        String next = ended ? "terminée" : ctx.services().recurring().nextOccurrence(r)
                .map(d -> "prochaine : " + Formats.date(d)).orElse("aucune échéance à venir");

        Label label = Widgets.label(r.label(), "op-label");
        VBox texts = new VBox(2, label, Widgets.label(detail, "op-detail"));
        VBox schedule = new VBox(2, Widgets.label(frequency, "op-detail"), Widgets.label(next, "op-detail"));
        schedule.setMinWidth(200);
        HBox badges = new HBox(6);
        if (r.type() == TransactionType.INCOME && !r.certain()) {
            badges.getChildren().add(Widgets.badge("incertain", "warning"));
        }
        if (r.type() == TransactionType.TRANSFER) {
            badges.getChildren().add(Widgets.badge("virement", "neutral"));
        }
        Label amount = Widgets.amount(f, r.signedAmount());
        amount.setMinWidth(120);
        amount.setAlignment(Pos.CENTER_RIGHT);

        Button edit = button("Modifier", () -> edit(r));
        Button stop = button("Arrêter", () -> {
            LocalDate today = ctx.services().planning().today();
            if (Dialogs.confirm(window(), "Arrêter la récurrence", "Plus aucune occurrence de « " + r.label()
                    + " » après aujourd'hui. L'historique est conservé.", "Arrêter")) {
                ctx.services().recurring().end(r.id(), today);
                ctx.events().fireChanged();
            }
        });
        stop.setDisable(ended);
        Button delete = button("Supprimer", () -> {
            if (Dialogs.confirm(window(), "Supprimer la récurrence", "Supprimer « " + r.label()
                    + " » ? Les opérations déjà validées restent dans l'historique.", "Supprimer")) {
                ctx.services().recurring().delete(r.id());
                ctx.events().fireChanged();
            }
        });
        HBox row = new HBox(14, texts, Widgets.spacer(), badges, schedule, amount, edit, stop, delete);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("op-row");
        return row;
    }

    private Button button(String text, Runnable action) {
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

    private void edit(RecurringRule r) {
        new RecurringDialog(ctx, r).showAndWait().ifPresent(saved -> ctx.events().fireChanged());
    }
}
