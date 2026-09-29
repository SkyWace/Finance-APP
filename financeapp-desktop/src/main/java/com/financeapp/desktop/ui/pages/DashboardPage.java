package com.financeapp.desktop.ui.pages;

import com.financeapp.core.account.Account;
import com.financeapp.core.dashboard.DashboardSummary;
import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.desktop.ui.common.Charts;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.AccountDialog;
import javafx.scene.Cursor;
import javafx.scene.chart.LineChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Reponse immediate aux questions : combien ai-je, combien vais-je avoir, combien puis-je depenser. */
public final class DashboardPage extends Page {

    private record Data(DashboardSummary summary, Forecast forecast, List<Account> accounts,
                        List<com.financeapp.core.budget.BudgetProgress> budgets) {
    }

    public DashboardPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Tableau de bord";
    }

    @Override
    public void refresh() {
        var s = ctx.services();
        UiAsync.load(() -> new Data(s.dashboard().summary(), s.forecast().forecast(30, 30), s.accounts().findAll(),
                s.budgets().progress(java.time.YearMonth.from(s.planning().today()))), this::render);
    }

    private void render(Data data) {
        content.getChildren().clear();
        if (data.accounts().stream().noneMatch(a -> !a.archived())) {
            content.getChildren().add(welcome());
            return;
        }
        Formats f = ctx.formats();
        DashboardSummary s = data.summary();

        VBox availableCard = Widgets.kpiCard("Disponible réel", f.rounded(s.available().available()),
                s.available().available().isNegative() ? "amount-negative" : "accent",
                "jusqu'au " + Formats.date(s.available().horizonEnd()) + " · voir le détail ›");
        availableCard.getStyleClass().add("clickable");
        availableCard.setCursor(Cursor.HAND);
        Tooltip.install(availableCard, new Tooltip("Solde actuel − dépenses prévues ± épargne + revenus certains"));
        availableCard.setOnMouseClicked(e -> ctx.navigate("available"));

        HBox row1 = new HBox(14,
                Widgets.kpiCard("Patrimoine financier", f.rounded(s.netWorth()), null, "Tous les comptes actifs"),
                Widgets.kpiCard("Comptes courants", f.rounded(s.currentAccounts()), null, null),
                Widgets.kpiCard("Épargne", f.rounded(s.savings()), null, null),
                availableCard);
        HBox row2 = new HBox(14,
                Widgets.kpiCard("Revenus ce mois", f.signed(s.monthIncome()), Formats.signClass(s.monthIncome()), null),
                Widgets.kpiCard("Dépenses ce mois", f.signed(s.monthExpenses()), Formats.signClass(s.monthExpenses()), null),
                Widgets.kpiCard("À venir d'ici la fin du mois", f.signed(s.upcomingThisMonth()), Formats.signClass(s.upcomingThisMonth()),
                        "Dépenses prévues et récurrentes"));

        LineChart<Number, Number> chart = Charts.balanceChart(data.forecast(), f);
        chart.setPrefHeight(300);
        String low = "Point bas prévu : " + f.money(data.forecast().lowestProjected().balance())
                + " le " + Formats.dayMonth(data.forecast().lowestProjected().date());
        VBox chartCard = Widgets.section("Solde des comptes courants — 30 jours", chart, Widgets.label(low, "muted"));
        HBox.setHgrow(chartCard, Priority.ALWAYS);

        Map<Long, String> accountNames = data.accounts().stream()
                .collect(Collectors.toMap(Account::id, Account::name));
        VBox upcoming = new VBox(2);
        for (PlannedItem i : s.nextOperations()) {
            upcoming.getChildren().add(Widgets.operationRow(f, i.date(), i.label(), accountNames.get(i.accountId()),
                    i.amount(), i.isOverdue(ctx.services().planning().today()) ? Widgets.badge("en retard", "warning") : null));
        }
        if (s.nextOperations().isEmpty()) {
            upcoming.getChildren().add(Widgets.emptyState("Aucune opération prévue dans les 30 prochains jours."));
        }
        Button seeUpcoming = link("Tout voir ›", "upcoming");
        VBox upcomingCard = Widgets.section("Prochaines opérations", upcoming, seeUpcoming);
        upcomingCard.setPrefWidth(400);
        upcomingCard.setMinWidth(340);

        VBox recent = new VBox(2);
        Map<Long, String> categoryNames = ctx.services().categories().fullNames();
        Function<Transaction, String> detail = t -> {
            String cat = t.categoryId() == null ? "" : categoryNames.getOrDefault(t.categoryId(), "");
            String acc = accountNames.getOrDefault(t.accountId(), "");
            return cat.isEmpty() ? acc : cat + " · " + acc;
        };
        for (Transaction t : s.recentTransactions()) {
            recent.getChildren().add(Widgets.operationRow(f, t.date(), t.label(), detail.apply(t), t.amount(),
                    t.isTransfer() ? Widgets.badge("virement", "neutral") : null));
        }
        if (s.recentTransactions().isEmpty()) {
            recent.getChildren().add(Widgets.emptyState("Aucune opération enregistrée pour l'instant."));
        }
        VBox recentCard = Widgets.section("Dernières opérations", recent, link("Toutes les transactions ›", "transactions"));
        HBox.setHgrow(recentCard, javafx.scene.layout.Priority.ALWAYS);
        HBox bottom = new HBox(14, recentCard);
        if (!data.budgets().isEmpty()) {
            VBox budgetList = new VBox(10);
            data.budgets().stream().limit(4).forEach(p -> budgetList.getChildren().add(BudgetsPage.budgetCard(p, f, true)));
            VBox budgetCard = Widgets.section("Budgets du mois", budgetList, link("Tous les budgets ›", "budgets"));
            budgetCard.setPrefWidth(400);
            budgetCard.setMinWidth(340);
            bottom.getChildren().add(budgetCard);
        }

        content.getChildren().addAll(row1, row2, new HBox(14, chartCard, upcomingCard), bottom);
        if (s.excludedAccounts() > 0) {
            content.getChildren().add(Widgets.label(s.excludedAccounts()
                    + " compte(s) dans une autre devise ne sont pas inclus dans ces totaux (aucune conversion automatique).", "muted"));
        }
    }

    private Button link(String text, String page) {
        Button b = new Button(text);
        b.getStyleClass().add("link");
        b.setOnAction(e -> ctx.navigate(page));
        return b;
    }

    private VBox welcome() {
        Label title = Widgets.label("Bienvenue dans " + ctx.services().properties().name(), "welcome-title");
        Label text = Widgets.label("""
                Commencez par créer votre compte courant avec son solde actuel. \
                Ajoutez ensuite vos opérations récurrentes (salaire, loyer, abonnements...) : \
                l'application calculera ce qui vous reste réellement disponible.""", "welcome-text");
        text.setWrapText(true);
        Button create = new Button("Créer mon premier compte");
        create.getStyleClass().add("primary");
        create.setOnAction(e -> new AccountDialog(ctx, null).showAndWait().ifPresent(a -> ctx.events().fireChanged()));
        Label privacy = Widgets.label("Vos données restent sur cet ordinateur. Aucun compte en ligne, aucune connexion bancaire.", "muted");
        VBox box = new VBox(16, title, text, create, privacy);
        box.getStyleClass().addAll("card", "welcome");
        box.setMaxWidth(640);
        return box;
    }
}
