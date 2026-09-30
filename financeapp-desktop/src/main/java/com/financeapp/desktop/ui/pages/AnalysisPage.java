package com.financeapp.desktop.ui.pages;

import com.financeapp.core.money.Money;
import com.financeapp.core.service.StatisticsService;
import com.financeapp.core.stats.CategoryAmount;
import com.financeapp.core.stats.CategoryComparison;
import com.financeapp.core.stats.MonthSummary;
import com.financeapp.desktop.ui.common.AccountFilter;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.MonthPicker;
import com.financeapp.desktop.ui.common.Progress;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Analyses : indicateur de trajectoire du mois, revenus vs depenses sur
 * 12 mois, repartition des depenses et comparaison avec le mois precedent.
 * Des donnees, pas des reproches : aucun jugement n'est formule.
 */
public final class AnalysisPage extends Page {

    private static final DateTimeFormatter SHORT_MONTH = DateTimeFormatter.ofPattern("MMM yy", Formats.LOCALE);

    private final MonthPicker picker;
    private final VBox body = new VBox(18);
    private final AccountFilter filter;
    private Long account;

    public AnalysisPage(UiContext ctx) {
        super(ctx);
        YearMonth current = YearMonth.from(ctx.services().planning().today());
        picker = new MonthPicker(current, current);
        picker.monthProperty().addListener((o, old, m) -> refresh());
        filter = new AccountFilter(ctx, this::refresh);
        content.getChildren().setAll(Widgets.row(Widgets.label("Mois analysé", "muted"), picker, filter.node()), body);
    }

    @Override
    public String title() {
        return "Analyses";
    }

    @Override
    public void refresh() {
        YearMonth month = picker.month();
        Long selected = filter.sync();
        account = selected;
        UiAsync.load(() -> ctx.services().statistics().report(month, month.minusMonths(1), 12, selected),
                r -> render(r, month));
    }

    private void render(StatisticsService.Report r, YearMonth month) {
        Formats f = ctx.formats();
        MonthSummary cur = r.months().getLast();
        MonthSummary prev = r.months().size() > 1 ? r.months().get(r.months().size() - 2) : null;
        String refName = MonthPicker.format(month.minusMonths(1)).toLowerCase(Formats.LOCALE);

        HBox trajectory = new HBox(14,
                Widgets.kpiCard("Revenus", f.money(cur.income()), null, null),
                Widgets.kpiCard("Dépenses", f.money(cur.expenses().negate()), null,
                        prev == null ? null : delta(f, cur.expenses().negate().minus(prev.expenses().negate())) + " vs " + refName),
                Widgets.kpiCard("Épargné", f.signed(cur.saved()), Formats.signClass(cur.saved()),
                        prev == null ? null : delta(f, cur.saved().minus(prev.saved())) + " vs " + refName),
                Widgets.kpiCard("Taux d'épargne", Formats.percent(cur.savingsRate()), null,
                        cur.savingsRate() == null ? "aucun revenu ce mois-ci" : "part des revenus non dépensée"));

        VBox comparison = comparison(r, f, month);
        HBox.setHgrow(comparison, Priority.ALWAYS);
        body.getChildren().setAll(trajectory, monthlyChart(r, f), new HBox(14, categories(r, f), comparison),
                merchants(f, month), customComparison(f, month));
    }

    private static String delta(Formats f, Money d) {
        return d.isZero() ? "= stable" : f.signed(d);
    }

    private VBox monthlyChart(StatisticsService.Report r, Formats f) {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setTickLabelsVisible(!f.isPrivacy());
        y.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Number n) {
                return String.format(Formats.LOCALE, "%,.0f", n.doubleValue());
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setAnimated(false);
        chart.setPrefHeight(300);
        chart.setBarGap(2);
        chart.setCategoryGap(14);
        chart.getStyleClass().add("income-expense-chart");
        XYChart.Series<String, Number> income = new XYChart.Series<>();
        income.setName("Revenus");
        XYChart.Series<String, Number> expenses = new XYChart.Series<>();
        expenses.setName("Dépenses");
        for (MonthSummary m : r.months()) {
            String label = SHORT_MONTH.format(m.month());
            // Conversion en double uniquement pour le trace.
            income.getData().add(tip(new XYChart.Data<>(label, m.income().amount().doubleValue()), f.money(m.income())));
            expenses.getData().add(tip(new XYChart.Data<>(label, m.expenses().negate().amount().doubleValue()),
                    f.money(m.expenses().negate())));
        }
        chart.getData().add(income);
        chart.getData().add(expenses);
        Label avg = Widgets.label("Dépenses mensuelles moyennes (mois complets) : " + f.money(r.averageExpenses()), "muted");
        return Widgets.section("Revenus et dépenses — 12 mois", chart, avg);
    }

    private static XYChart.Data<String, Number> tip(XYChart.Data<String, Number> d, String text) {
        d.nodeProperty().addListener((o, old, node) -> {
            if (node != null) {
                Tooltip.install(node, new Tooltip(d.getXValue() + " : " + text));
            }
        });
        return d;
    }

    private VBox categories(StatisticsService.Report r, Formats f) {
        VBox list = new VBox(8);
        for (CategoryAmount c : r.categories()) {
            Label name = Widgets.label(c.name(), "op-label");
            Label amount = Widgets.label(f.money(c.amount()) + " · " + Formats.percent(c.share()), "op-detail");
            list.getChildren().add(new VBox(3, Widgets.row(name, Widgets.spacer(), amount),
                    Progress.bar(c.share().doubleValue() / 100.0, "progress-neutral")));
        }
        if (r.categories().isEmpty()) {
            list.getChildren().add(Widgets.emptyState("Aucune dépense ce mois-ci."));
        }
        VBox box = Widgets.section("Dépenses par catégorie", list);
        box.setPrefWidth(420);
        box.setMinWidth(340);
        return box;
    }

    private VBox comparison(StatisticsService.Report r, Formats f, YearMonth month) {
        GridPane table = new GridPane();
        table.setHgap(18);
        table.setVgap(6);
        String a = MonthPicker.format(month.minusMonths(1));
        String b = MonthPicker.format(month);
        String[] headers = {"Catégorie", a, b, "Écart", "Évolution"};
        for (int i = 0; i < headers.length; i++) {
            table.add(Widgets.label(headers[i], "table-header"), i, 0);
        }
        int row = 1;
        for (CategoryComparison c : r.comparison()) {
            addComparison(table, row++, c, f, false);
        }
        addComparison(table, row, r.totalComparison(), f, true);
        Label note = Widgets.label("Les montants absolus accompagnent toujours les pourcentages : "
                + "une hausse de 50 % sur 4 € n'a pas le même poids qu'une hausse de 50 % sur 400 €.", "muted");
        note.setWrapText(true);
        return Widgets.section("Comparaison avec le mois précédent", table, note);
    }

    /** Principaux commercants / libelles du mois (libelles bancaires regroupes). */
    private VBox merchants(Formats f, YearMonth month) {
        var stats = ctx.services().statistics().merchants(month.atDay(1), month.atEndOfMonth(), 10, account);
        GridPane table = new GridPane();
        table.setHgap(18);
        table.setVgap(6);
        String[] headers = {"Commerçant / libellé", "Opérations", "Total", "Moyenne"};
        for (int i = 0; i < headers.length; i++) {
            table.add(Widgets.label(headers[i], "table-header"), i, 0);
        }
        int row = 1;
        for (var s : stats) {
            table.add(Widgets.label(s.label(), "op-label"), 0, row);
            table.add(right(Widgets.label(Integer.toString(s.count()), "op-detail")), 1, row);
            table.add(right(Widgets.label(f.money(s.total()), "op-label")), 2, row);
            table.add(right(Widgets.label(f.money(s.average()), "op-detail")), 3, row);
            row++;
        }
        if (stats.isEmpty()) {
            return Widgets.section("Principaux commerçants", Widgets.emptyState("Aucune dépense ce mois-ci."));
        }
        return Widgets.section("Principaux commerçants — " + MonthPicker.format(month), table,
                Widgets.label("Les libellés bancaires sont regroupés (« CB CARREFOUR 12/09 » et « CARREFOUR » ensemble).", "muted"));
    }

    /** Comparaison de deux periodes quelconques (ex. ete 2025 vs ete 2026). */
    private VBox customComparison(Formats f, YearMonth month) {
        var refFrom = Widgets.datePicker(month.minusYears(1).atDay(1));
        var refTo = Widgets.datePicker(month.minusYears(1).atEndOfMonth());
        var from = Widgets.datePicker(month.atDay(1));
        var to = Widgets.datePicker(month.atEndOfMonth());
        for (var p : List.of(refFrom, refTo, from, to)) {
            p.setPrefWidth(135);
        }
        GridPane result = new GridPane();
        result.setHgap(18);
        result.setVgap(6);
        javafx.scene.control.Button compare = new javafx.scene.control.Button("Comparer");
        compare.getStyleClass().add("secondary");
        compare.setOnAction(e -> {
            try {
                var cmp = ctx.services().statistics().comparePeriods(Widgets.dateValue(refFrom), Widgets.dateValue(refTo),
                        Widgets.dateValue(from), Widgets.dateValue(to), account);
                result.getChildren().clear();
                String[] headers = {"Catégorie", "Période de référence", "Période comparée", "Écart", "Évolution"};
                for (int i = 0; i < headers.length; i++) {
                    result.add(Widgets.label(headers[i], "table-header"), i, 0);
                }
                int row = 1;
                for (CategoryComparison c : cmp.categories()) {
                    addComparison(result, row++, c, f, false);
                }
                addComparison(result, row, cmp.total(), f, true);
            } catch (RuntimeException ex) {
                com.financeapp.desktop.ui.common.Dialogs.error(window(), ex);
            }
        });
        return Widgets.section("Comparer deux périodes",
                Widgets.row(Widgets.label("Référence : du", "muted"), refFrom, Widgets.label("au", "muted"), refTo),
                Widgets.row(Widgets.label("Comparée : du", "muted"), from, Widgets.label("au", "muted"), to, compare),
                result);
    }

    private static void addComparison(GridPane table, int row, CategoryComparison c, Formats f, boolean total) {
        String style = total ? "total-label" : "op-label";
        table.add(Widgets.label(c.name(), style), 0, row);
        table.add(right(Widgets.label(f.money(c.reference()), "op-detail")), 1, row);
        table.add(right(Widgets.label(f.money(c.current()), "op-label")), 2, row);
        // Pour des depenses, une hausse est affichee avec son signe, sans couleur de jugement.
        table.add(right(Widgets.label(c.delta().isZero() ? "=" : f.signed(c.delta()), "op-label")), 3, row);
        table.add(right(Widgets.label(Formats.signedPercent(c.percentChange()), "op-detail")), 4, row);
    }

    private static Label right(Label l) {
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER_RIGHT);
        return l;
    }
}
