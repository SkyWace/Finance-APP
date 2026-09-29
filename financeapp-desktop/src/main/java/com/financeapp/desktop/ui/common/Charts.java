package com.financeapp.desktop.ui.common;

import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.forecast.ForecastPoint;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Tooltip;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.List;

/** Graphiques de solde : historique reel (trait plein) et prevision (pointilles). */
public final class Charts {

    private Charts() {
    }

    public static LineChart<Number, Number> balanceChart(Forecast forecast, Formats formats) {
        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(false);
        x.setAutoRanging(false);
        long from = forecast.history().isEmpty() ? forecast.projection().getFirst().date().toEpochDay()
                : forecast.history().getFirst().date().toEpochDay();
        long to = forecast.projection().getLast().date().toEpochDay();
        x.setLowerBound(from);
        x.setUpperBound(Math.max(to, from + 1));
        long span = Math.max(1, to - from);
        x.setTickUnit(Math.max(1, Math.round(span / 8.0)));
        x.setMinorTickVisible(false);
        x.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                return Formats.shortDate(LocalDate.ofEpochDay(n.longValue()));
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });

        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(false);
        y.setTickLabelsVisible(!formats.isPrivacy());
        y.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                return String.format(Formats.LOCALE, "%,.0f", n.doubleValue());
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });

        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setCreateSymbols(true);
        chart.setLegendVisible(true);
        chart.getStyleClass().add("balance-chart");
        // Valeurs converties en double UNIQUEMENT pour le trace ; aucun calcul n'en depend.
        chart.getData().add(series("Réel", forecast.history(), formats));
        chart.getData().add(series("Prévision", forecast.projection(), formats));
        return chart;
    }

    private static XYChart.Series<Number, Number> series(String name, List<ForecastPoint> points, Formats formats) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        for (ForecastPoint p : points) {
            XYChart.Data<Number, Number> d = new XYChart.Data<>(p.date().toEpochDay(), p.balance().amount().doubleValue());
            s.getData().add(d);
            String tip = Formats.longDate(p.date()) + "\n" + formats.money(p.balance())
                    + (p.projected() ? "  (prévision)" : "  (réel)");
            d.nodeProperty().addListener((o, old, node) -> {
                if (node != null) {
                    Tooltip.install(node, new Tooltip(tip));
                }
            });
        }
        return s;
    }
}
