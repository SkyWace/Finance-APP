package com.financeapp.desktop.ui.common;

import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.forecast.ForecastPoint;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Tooltip;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Graphiques de solde : historique reel (trait plein) et prevision (pointilles). */
public final class Charts {

    /** Au-dela, les points sont echantillonnes (le point bas et les extremites sont toujours conserves). */
    private static final int MAX_POINTS = 400;
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yy", Formats.LOCALE);

    private Charts() {
    }

    public static LineChart<Number, Number> balanceChart(Forecast forecast, Formats formats) {
        long from = forecast.history().isEmpty() ? forecast.projection().getFirst().date().toEpochDay()
                : forecast.history().getFirst().date().toEpochDay();
        LineChart<Number, Number> chart = chart(from, forecast.projection().getLast().date().toEpochDay(), formats);
        chart.getStyleClass().add("balance-chart");
        // Valeurs converties en double UNIQUEMENT pour le trace ; aucun calcul n'en depend.
        chart.getData().add(series("Réel", sample(forecast.history(), null), formats, "réel"));
        chart.getData().add(series("Prévision", sample(forecast.projection(), forecast.lowestProjected()), formats,
                "prévision"));
        return chart;
    }

    /** Deux projections superposees : situation actuelle et scenario simule. */
    public static LineChart<Number, Number> comparisonChart(Forecast baseline, Forecast scenario, Formats formats) {
        LineChart<Number, Number> chart = chart(baseline.projection().getFirst().date().toEpochDay(),
                baseline.projection().getLast().date().toEpochDay(), formats);
        chart.getStyleClass().add("comparison-chart");
        chart.getData().add(series("Sans la simulation", sample(baseline.projection(), baseline.lowestProjected()),
                formats, "sans la simulation"));
        chart.getData().add(series("Avec la simulation", sample(scenario.projection(), scenario.lowestProjected()),
                formats, "avec la simulation"));
        return chart;
    }

    private static LineChart<Number, Number> chart(long from, long to, Formats formats) {
        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(false);
        x.setAutoRanging(false);
        x.setLowerBound(from);
        x.setUpperBound(Math.max(to, from + 1));
        long span = Math.max(1, to - from);
        x.setTickUnit(Math.max(1, Math.round(span / 8.0)));
        x.setMinorTickVisible(false);
        boolean long_ = span > 200;
        x.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                LocalDate d = LocalDate.ofEpochDay(n.longValue());
                return long_ ? MONTH_YEAR.format(d) : Formats.shortDate(d);
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
        return chart;
    }

    /** Reduit une serie longue (48 mois = 1 460 jours) sans perdre le point bas ni les extremites. */
    static List<ForecastPoint> sample(List<ForecastPoint> points, ForecastPoint keep) {
        if (points.size() <= MAX_POINTS) {
            return points;
        }
        int step = (int) Math.ceil(points.size() / (double) MAX_POINTS);
        List<ForecastPoint> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            ForecastPoint p = points.get(i);
            if (i % step == 0 || i == points.size() - 1 || p.equals(keep)) {
                result.add(p);
            }
        }
        return result;
    }

    private static XYChart.Series<Number, Number> series(String name, List<ForecastPoint> points, Formats formats,
                                                         String kind) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        for (ForecastPoint p : points) {
            XYChart.Data<Number, Number> d = new XYChart.Data<>(p.date().toEpochDay(), p.balance().amount().doubleValue());
            s.getData().add(d);
            String tip = Formats.longDate(p.date()) + "\n" + formats.money(p.balance()) + "  (" + kind + ")";
            d.nodeProperty().addListener((o, old, node) -> {
                if (node != null) {
                    Tooltip.install(node, new Tooltip(tip));
                }
            });
        }
        return s;
    }
}
