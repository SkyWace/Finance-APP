package com.financeapp.desktop.ui.pages;

import com.financeapp.core.account.Account;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.ConfirmOccurrenceDialog;
import com.financeapp.desktop.ui.dialogs.TransactionDialog;
import com.financeapp.desktop.ui.dialogs.TransferDialog;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Operations a venir : transactions prevues et occurrences de recurrences.
 * Chaque occurrence peut etre validee (elle devient une operation effectuee)
 * ou ignoree ; les retards restent visibles et comptes.
 */
public final class UpcomingPage extends Page {

    private final ComboBox<Choice<Integer>> range = new ComboBox<>();

    public UpcomingPage(UiContext ctx) {
        super(ctx);
        range.getItems().setAll(new Choice<>(7, "7 prochains jours"), new Choice<>(30, "30 prochains jours"),
                new Choice<>(60, "60 prochains jours"), new Choice<>(90, "90 prochains jours"));
        Widgets.select(range, 30);
        range.setOnAction(e -> refresh());
    }

    @Override
    public String title() {
        return "À venir";
    }

    @Override
    public void refresh() {
        Formats f = ctx.formats();
        LocalDate today = ctx.services().planning().today();
        int days = Widgets.selected(range) == null ? 30 : Widgets.selected(range);
        List<PlannedItem> items = ctx.services().planning().upcomingForDisplay(today.plusDays(days));
        Map<Long, Account> accounts = ctx.services().accounts().findAll().stream()
                .collect(Collectors.toMap(Account::id, a -> a));
        Map<Long, String> categories = ctx.services().categories().fullNames();
        Currency base = ctx.services().settings().baseCurrency();

        Money zero = Money.zero(base);
        Money out = zero;
        Money in = zero;
        for (PlannedItem i : items) {
            if (i.type() == TransactionType.TRANSFER || !i.amount().currency().equals(base)) {
                continue;
            }
            if (i.amount().isNegative()) {
                out = out.plus(i.amount());
            } else {
                in = in.plus(i.amount());
            }
        }
        HBox kpis = new HBox(14,
                Widgets.kpiCard("Sorties prévues", f.signed(out), Formats.signClass(out), null),
                Widgets.kpiCard("Entrées prévues", f.signed(in), Formats.signClass(in), null),
                Widgets.kpiCard("Opérations", Integer.toString(items.size()), null, "sur la période"));

        VBox list = new VBox(4);
        TreeMap<LocalDate, List<PlannedItem>> byDay = items.stream()
                .collect(Collectors.groupingBy(i -> i.isOverdue(today) ? today.minusDays(1) : i.date(), TreeMap::new,
                        Collectors.toList()));
        for (var entry : byDay.entrySet()) {
            boolean overdue = entry.getKey().isBefore(today);
            String heading = overdue ? "En retard — à valider ou ignorer"
                    : entry.getKey().equals(today) ? "Aujourd'hui" : Formats.longDate(entry.getKey());
            list.getChildren().add(Widgets.label(heading, overdue ? "day-heading-warning" : "day-heading"));
            for (PlannedItem i : entry.getValue()) {
                list.getChildren().add(itemRow(i, accounts, categories, today, f));
            }
        }
        if (items.isEmpty()) {
            list.getChildren().add(Widgets.emptyState("Rien de prévu sur cette période. Ajoutez des opérations récurrentes "
                    + "(loyer, salaire, abonnements…) ou des opérations futures avec le statut « Prévu »."));
        }
        content.getChildren().setAll(Widgets.row(Widgets.label("Période", "muted"), range), kpis,
                Widgets.section(null, list));
    }

    private HBox itemRow(PlannedItem i, Map<Long, Account> accounts, Map<Long, String> categories,
                         LocalDate today, Formats f) {
        String account = accounts.containsKey(i.accountId()) ? accounts.get(i.accountId()).name() : "";
        String detail;
        if (i.type() == TransactionType.TRANSFER) {
            Account to = i.transferAccountId() == null ? null : accounts.get(i.transferAccountId());
            detail = account + " → " + (to == null ? "?" : to.name());
        } else {
            String cat = i.categoryId() == null ? "" : categories.getOrDefault(i.categoryId(), "");
            detail = cat.isEmpty() ? account : cat + " · " + account;
        }
        Label source = i.source() == PlannedItem.Source.RECURRING
                ? Widgets.badge("récurrent", "info") : Widgets.badge("prévu", "neutral");
        HBox row = Widgets.operationRow(f, i.date(), i.label(), detail, i.amount(),
                i.isOverdue(today) ? Widgets.badge("en retard", "warning") : source);

        if (i.source() == PlannedItem.Source.RECURRING) {
            row.getChildren().addAll(
                    button("Valider", "secondary", () -> new ConfirmOccurrenceDialog(ctx, i).showAndWait()
                            .ifPresent(r -> ctx.events().fireChanged())),
                    button("Ignorer", "ghost", () -> {
                        if (Dialogs.confirm(window(), "Ignorer l'occurrence", "Ignorer « " + i.label() + " » du "
                                + Formats.date(i.date()) + " ? Elle ne sera plus comptée (trace conservée comme annulée).",
                                "Ignorer")) {
                            ctx.services().recurring().skip(i.recurringId(), i.date());
                            ctx.events().fireChanged();
                        }
                    }));
        } else {
            row.getChildren().addAll(
                    button("Effectuée", "secondary", () -> {
                        ctx.services().transactions().setStatus(i.transactionId(), TransactionStatus.COMPLETED);
                        ctx.events().fireChanged();
                    }),
                    button("Modifier", "ghost", () -> {
                        var t = ctx.services().transactions().get(i.transactionId());
                        var dialog = t.isTransfer() ? new TransferDialog(ctx, t) : new TransactionDialog(ctx, t, t.type(), null);
                        dialog.showAndWait().ifPresent(r -> ctx.events().fireChanged());
                    }));
        }
        return row;
    }

    private Button button(String text, String style, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll(style, "compact");
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
