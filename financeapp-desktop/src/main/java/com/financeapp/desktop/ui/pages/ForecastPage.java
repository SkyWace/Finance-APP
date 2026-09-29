package com.financeapp.desktop.ui.pages;

import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.forecast.ForecastPoint;
import com.financeapp.desktop.ui.common.Charts;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.chart.LineChart;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Evolution previsionnelle du solde des comptes du perimetre "disponible". */
public final class ForecastPage extends Page {

    private final ToggleGroup rangeGroup = new ToggleGroup();
    private final VBox body = new VBox(18);
    private int days = 30;

    public ForecastPage(UiContext ctx) {
        super(ctx);
        HBox ranges = new HBox(0);
        int[] projections = {7, 30, 90, 180, 365};
        String[] labels = {"7 jours", "30 jours", "3 mois", "6 mois", "12 mois"};
        for (int i = 0; i < projections.length; i++) {
            int projection = projections[i];
            ToggleButton b = new ToggleButton(labels[i]);
            b.getStyleClass().add("segment");
            b.setToggleGroup(rangeGroup);
            b.setUserData(projection);
            b.setSelected(projection == days);
            ranges.getChildren().add(b);
        }
        rangeGroup.selectedToggleProperty().addListener((o, old, t) -> {
            if (t == null) {
                old.setSelected(true);
                return;
            }
            days = (int) t.getUserData();
            refresh();
        });
        content.getChildren().setAll(Widgets.row(Widgets.label("Horizon", "muted"), ranges), body);
    }

    @Override
    public String title() {
        return "Prévisions";
    }

    @Override
    public void refresh() {
        int projection = days;
        int history = Math.min(90, Math.max(7, projection / 2));
        UiAsync.load(() -> ctx.services().forecast().forecast(history, projection), this::render);
    }

    private void render(Forecast forecast) {
        Formats f = ctx.formats();
        ForecastPoint today = forecast.projection().getFirst();
        ForecastPoint low = forecast.lowestProjected();
        HBox kpis = new HBox(14,
                Widgets.kpiCard("Aujourd'hui", f.money(forecast.history().isEmpty() ? today.balance()
                        : forecast.history().getLast().balance()), null, "solde réel"),
                Widgets.kpiCard("Point bas", f.money(low.balance()), low.balance().isNegative() ? "amount-negative" : null,
                        "le " + Formats.date(low.date())),
                Widgets.kpiCard("Fin de période", f.money(forecast.endBalance()), null,
                        Formats.date(forecast.projection().getLast().date())));
        LineChart<Number, Number> chart = Charts.balanceChart(forecast, f);
        chart.setPrefHeight(420);
        VBox chartCard = Widgets.section("Solde réel (trait plein) et prévu (pointillés)", chart);
        body.getChildren().setAll(kpis, chartCard);
        forecast.firstNegative().ifPresent(p -> body.getChildren().add(1, Widgets.badge(
                "⚠ Solde prévu négatif à partir du " + Formats.date(p.date()) + " (" + f.money(p.balance()) + ")", "danger")));
        body.getChildren().add(Widgets.label("La prévision part du solde actuel des comptes inclus dans le disponible et "
                + "applique les opérations prévues et récurrentes. Les dépenses non planifiées (courses, loisirs…) "
                + "ne sont pas estimées : ajoutez-les comme opérations prévues ou récurrentes pour les prendre en compte.",
                "muted"));
        ((javafx.scene.control.Label) body.getChildren().getLast()).setWrapText(true);
    }
}
