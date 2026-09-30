package com.financeapp.desktop.ui.pages;

import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.money.Money;
import com.financeapp.core.simulation.GoalImpact;
import com.financeapp.core.simulation.LoanPreview;
import com.financeapp.core.simulation.MonthComparison;
import com.financeapp.core.simulation.MonthlyPicture;
import com.financeapp.core.simulation.Simulation;
import com.financeapp.core.simulation.SimulationItem;
import com.financeapp.core.simulation.SimulationItem.Kind;
import com.financeapp.core.simulation.SimulationResult;
import com.financeapp.desktop.ui.common.Charts;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.FinancedPurchaseDialog;
import com.financeapp.desktop.ui.dialogs.SimulationItemDialog;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Mode simulation "What If?" : on teste une decision (achat, credit, nouvelle
 * charge, resiliation…) et on voit son effet sur le disponible mensuel, la
 * capacite d'epargne, le solde et les objectifs. Les scenarios sont enregistres
 * a part ; les donnees reelles ne sont jamais modifiees.
 */
public final class SimulationsPage extends Page {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Formats.LOCALE);
    private static final DateTimeFormatter SHORT_MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Formats.LOCALE);

    private Long currentId;

    public SimulationsPage(UiContext ctx) {
        super(ctx);
    }

    @Override
    public String title() {
        return "Simulations";
    }

    @Override
    public void refresh() {
        List<Simulation> all = ctx.services().simulations().findAll();
        Label banner = Widgets.label("Mode simulation : ce que vous testez ici ne modifie jamais vos opérations, "
                + "récurrences, budgets ou soldes réels.", "sim-banner");
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);

        Button create = new Button("+  Nouvelle simulation");
        create.getStyleClass().add("primary");
        create.setOnAction(e -> askName("Nouvelle simulation", "").ifPresent(name ->
                open(ctx.services().simulations().save(new Simulation(null, name, 12, List.of())))));
        Button purchase = new Button("Achat financé à crédit…");
        purchase.getStyleClass().add("secondary");
        purchase.setOnAction(e -> new FinancedPurchaseDialog(ctx).showAndWait().ifPresent(items -> {
            String name = items.stream().filter(i -> i.kind() == Kind.LOAN || i.kind() == Kind.ONE_TIME)
                    .findFirst().map(i -> "Achat " + i.label().replaceFirst("^[^—]*— ", "")).orElse("Achat");
            open(ctx.services().simulations().save(new Simulation(null, name, 12, items)));
        }));

        if (all.isEmpty()) {
            currentId = null;
            content.getChildren().setAll(banner, Widgets.row(create, purchase), Widgets.section(null, Widgets.emptyState(
                    "Testez une décision avant de la prendre : acheter une voiture à crédit, changer de loyer, "
                            + "résilier un abonnement, accepter un nouveau salaire… L'application compare votre "
                            + "disponible mensuel, votre capacité d'épargne et l'évolution de votre solde avant et après.")));
            return;
        }
        Simulation current = all.stream().filter(s -> s.id().equals(currentId)).findFirst().orElse(all.getFirst());
        currentId = current.id();

        ComboBox<Choice<Long>> selector = new ComboBox<>();
        for (Simulation s : all) {
            selector.getItems().add(new Choice<>(s.id(), s.name()));
        }
        Widgets.select(selector, currentId);
        selector.setPrefWidth(280);
        selector.valueProperty().addListener((o, old, c) -> {
            if (c != null && !c.value().equals(currentId)) {
                currentId = c.value();
                refresh();
            }
        });
        Button rename = small("Renommer", () -> askName("Renommer la simulation", current.name()).ifPresent(name ->
                save(new Simulation(current.id(), name, current.horizonMonths(), current.items()))));
        Button delete = small("Supprimer", () -> {
            if (Dialogs.confirm(window(), "Supprimer la simulation", "Supprimer « " + current.name() + " » ?", "Supprimer")) {
                ctx.services().simulations().delete(current.id());
                currentId = null;
                refresh();
            }
        });
        HBox top = Widgets.row(Widgets.label("Simulation", "muted"), selector, rename, delete, Widgets.spacer(), create, purchase);

        VBox results = new VBox(18);
        content.getChildren().setAll(banner, top, editor(current), results);
        results.getChildren().setAll(Widgets.label("Calcul…", "muted"));
        UiAsync.load(() -> ctx.services().simulations().run(current), r -> results.getChildren().setAll(results(r)),
                ex -> results.getChildren().setAll(Widgets.section(null, Widgets.emptyState(ex.getMessage()))));
    }

    // ---------------------------------------------------------------- hypotheses

    private VBox editor(Simulation sim) {
        Formats f = ctx.formats();
        HBox horizons = new HBox(0);
        ToggleGroup group = new ToggleGroup();
        for (int months : Simulation.HORIZONS) {
            ToggleButton b = new ToggleButton(months + " mois");
            b.getStyleClass().add("segment");
            b.setToggleGroup(group);
            b.setSelected(months == sim.horizonMonths());
            b.setOnAction(e -> {
                if (months != sim.horizonMonths()) {
                    save(new Simulation(sim.id(), sim.name(), months, sim.items()));
                } else {
                    b.setSelected(true);
                }
            });
            horizons.getChildren().add(b);
        }

        MenuButton add = new MenuButton("+  Ajouter une hypothèse");
        add.getStyleClass().add("secondary");
        for (Kind kind : Kind.values()) {
            MenuItem item = new MenuItem(kind.label());
            item.setOnAction(e -> new SimulationItemDialog(ctx, kind, null).showAndWait()
                    .ifPresent(i -> save(sim.withItems(append(sim.items(), i)))));
            add.getItems().add(item);
        }
        MenuItem purchase = new MenuItem("Achat financé à crédit…");
        purchase.setOnAction(e -> new FinancedPurchaseDialog(ctx).showAndWait().ifPresent(items -> {
            List<SimulationItem> all = new ArrayList<>(sim.items());
            all.addAll(items);
            save(sim.withItems(all));
        }));
        add.getItems().add(purchase);

        VBox list = new VBox(2);
        for (int i = 0; i < sim.items().size(); i++) {
            SimulationItem item = sim.items().get(i);
            int index = i;
            VBox texts = new VBox(1, Widgets.label(item.label(), "op-label"),
                    Widgets.label(item.kind().label() + " · " + describe(item, f), "op-detail"));
            HBox row = new HBox(12, texts, Widgets.spacer(),
                    small("Modifier", () -> new SimulationItemDialog(ctx, item.kind(), item).showAndWait().ifPresent(n -> {
                        List<SimulationItem> items = new ArrayList<>(sim.items());
                        items.set(index, n);
                        save(sim.withItems(items));
                    })),
                    small("Retirer", () -> {
                        List<SimulationItem> items = new ArrayList<>(sim.items());
                        items.remove(index);
                        save(sim.withItems(items));
                    }));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("op-row");
            list.getChildren().add(row);
        }
        if (sim.items().isEmpty()) {
            list.getChildren().add(Widgets.emptyState("Aucune hypothèse : ajoutez une dépense, un montant mensuel, "
                    + "un crédit ou l'arrêt d'une récurrence."));
        }
        return Widgets.section("Hypothèses", Widgets.row(Widgets.label("Période analysée", "muted"), horizons,
                Widgets.spacer(), add), list);
    }

    private String describe(SimulationItem i, Formats f) {
        return switch (i.kind()) {
            case ONE_TIME -> f.signed(i.amount()) + " le " + Formats.date(i.date());
            case MONTHLY -> f.signed(i.amount()) + " par mois à partir du " + Formats.date(i.date())
                    + (i.months() == null ? "" : ", pendant " + i.months() + " mois");
            case LOAN -> f.money(i.amount()) + " sur " + i.months() + " mois"
                    + (i.annualRate() == null ? "" : " à " + Formats.percent(i.annualRate()))
                    + (i.payment() == null ? "" : ", mensualité " + f.money(i.payment()))
                    + " · 1re mensualité le " + Formats.date(i.date());
            case STOP_RECURRING -> "plus d'occurrence à partir du " + Formats.date(i.date());
        };
    }

    // ---------------------------------------------------------------- resultats

    private List<Node> results(SimulationResult r) {
        Formats f = ctx.formats();
        String period = SHORT_MONTH.format(r.firstMonth()) + " → " + SHORT_MONTH.format(r.lastMonth());
        Money impact = r.availableImpact();
        HBox kpis = new HBox(14,
                Widgets.kpiCard("Avant · disponible moyen", f.money(r.before().available()) + " / mois", null, period),
                Widgets.kpiCard("Après · disponible moyen", f.money(r.after().available()) + " / mois",
                        r.after().available().isNegative() ? "amount-negative" : null, "avec les hypothèses"),
                Widgets.kpiCard("Impact moyen", (impact.isZero() ? "= " : "") + f.signed(impact) + " / mois",
                        null, (impact.isNegative() ? "▼ baisse" : impact.isPositive() ? "▲ hausse" : "aucun changement")
                                + typicalMonth(r).map(m -> " · mois type : " + (m.isZero() ? "=" : f.signed(m)) + " / mois")
                                .orElse("")));

        List<Node> nodes = new ArrayList<>(List.of(kpis, pictureTable(r, f), balance(r, f)));
        nodes.add(monthsTable(r, f));
        if (!r.loans().isEmpty()) {
            VBox list = new VBox(4);
            for (LoanPreview l : r.loans()) {
                list.getChildren().add(Widgets.label(l.label() + " : " + f.money(l.principal()) + " → " + f.money(l.payment())
                        + " par mois · taux " + Formats.percent(l.annualRate()) + (l.rateEstimated() ? " (estimé)" : "")
                        + " · dernière mensualité " + MONTH.format(l.endDate()) + " · intérêts " + f.money(l.totalInterest()),
                        "op-label"));
            }
            nodes.add(Widgets.section("Crédits simulés", list));
        }
        nodes.add(goals(r, f));
        Label method = Widgets.label("Méthode : moyennes mensuelles sur " + r.months().size() + " mois complets à partir de "
                + MONTH.format(r.firstMonth()) + ", calculées à partir de vos opérations prévues et récurrentes réelles "
                + "(comptes inclus dans le disponible), des dépenses courantes estimées (moyenne des derniers mois, hors "
                + "récurrences) et des hypothèses ci-dessus. Les virements vers vos livrets comptent comme épargne programmée.",
                "muted");
        method.setWrapText(true);
        nodes.add(method);
        return nodes;
    }

    /**
     * Ecart mensuel le plus frequent : l'impact "de croisiere" d'une decision, hors
     * depenses ponctuelles (un apport lisse sur la moyenne, par exemple).
     */
    private static Optional<Money> typicalMonth(SimulationResult r) {
        java.util.Map<Money, Integer> counts = new java.util.LinkedHashMap<>();
        for (MonthComparison m : r.months()) {
            counts.merge(m.impact(), 1, Integer::sum);
        }
        return counts.entrySet().stream().filter(e -> e.getValue() > 1)
                .max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey)
                .filter(m -> !m.equals(r.availableImpact()));
    }

    private VBox pictureTable(SimulationResult r, Formats f) {
        GridPane grid = new GridPane();
        grid.setHgap(28);
        grid.setVgap(6);
        String[] headers = {"Par mois (moyenne)", "Avant", "Après", "Écart"};
        for (int i = 0; i < headers.length; i++) {
            grid.add(Widgets.label(headers[i], "table-header"), i, 0);
        }
        record Line(String label, Function<MonthlyPicture, Money> value, boolean total) {
        }
        List<Line> lines = List.of(
                new Line("Revenus", MonthlyPicture::income, false),
                new Line("Charges fixes (récurrences, mensualités)", MonthlyPicture::fixedCharges, false),
                new Line("Reste à vivre", MonthlyPicture::livingRemainder, true),
                new Line("Dépenses ponctuelles prévues", MonthlyPicture::oneOffExpenses, false),
                new Line("Dépenses courantes estimées", MonthlyPicture::variableSpending, false),
                new Line("Épargne programmée", MonthlyPicture::scheduledSavings, false),
                new Line("Disponible mensuel", MonthlyPicture::available, true),
                new Line("Capacité d'épargne", MonthlyPicture::savingCapacity, true));
        int row = 1;
        for (Line l : lines) {
            Money before = l.value().apply(r.before());
            Money after = l.value().apply(r.after());
            Money delta = after.minus(before);
            String style = l.total() ? "total-label" : "op-label";
            grid.add(Widgets.label(l.label(), style), 0, row);
            grid.add(right(Widgets.label(f.money(before), "op-detail")), 1, row);
            grid.add(right(Widgets.label(f.money(after), "op-label")), 2, row);
            grid.add(right(Widgets.label(delta.isZero() ? "=" : f.signed(delta), "op-label")), 3, row);
            row++;
        }
        Label note = Widgets.label("Reste à vivre = revenus − charges fixes. Disponible = reste à vivre − dépenses "
                + "ponctuelles − dépenses courantes − épargne programmée. Capacité d'épargne = disponible + épargne programmée.",
                "hint");
        note.setWrapText(true);
        return Widgets.section("Avant / après", grid, note);
    }

    private VBox balance(SimulationResult r, Formats f) {
        Forecast base = r.baselineForecast();
        Forecast scenario = r.scenarioForecast();
        LineChart<Number, Number> chart = Charts.comparisonChart(base, scenario, f);
        chart.setPrefHeight(340);
        HBox kpis = new HBox(14,
                Widgets.kpiCard("Point bas sans / avec", f.money(base.lowestProjected().balance()) + "  →  "
                        + f.money(scenario.lowestProjected().balance()), null, "le " + Formats.date(base.lowestProjected().date())
                        + "  →  le " + Formats.date(scenario.lowestProjected().date())),
                Widgets.kpiCard("Solde fin de période sans / avec", f.money(base.endBalance()) + "  →  "
                        + f.money(scenario.endBalance()), null, Formats.date(scenario.projection().getLast().date())));
        VBox box = Widgets.section("Évolution du solde prévu (pointillés : sans la simulation · trait plein : avec)", kpis, chart);
        scenario.firstNegative().ifPresent(p -> box.getChildren().add(1, Widgets.badge("⚠ Avec la simulation, le solde "
                + "prévu passe sous zéro le " + Formats.date(p.date()) + " (" + f.money(p.balance()) + ")", "danger")));
        return box;
    }

    private VBox monthsTable(SimulationResult r, Formats f) {
        GridPane grid = new GridPane();
        grid.setHgap(28);
        grid.setVgap(4);
        String[] headers = {"Mois", "Disponible sans", "Avec", "Écart"};
        for (int i = 0; i < headers.length; i++) {
            grid.add(Widgets.label(headers[i], "table-header"), i, 0);
        }
        int row = 1;
        for (MonthComparison m : r.months()) {
            grid.add(Widgets.label(Formats.capitalize(SHORT_MONTH.format(m.month())), "op-label"), 0, row);
            grid.add(right(Widgets.label(f.money(m.before()), "op-detail")), 1, row);
            grid.add(right(Widgets.label(f.money(m.after()), "op-label")), 2, row);
            grid.add(right(Widgets.label(m.impact().isZero() ? "=" : f.signed(m.impact()), "op-label")), 3, row);
            row++;
        }
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(300, 20.5 * (r.months().size() + 1)));
        scroll.getStyleClass().add("page-scroll");
        return Widgets.section("Mois par mois", scroll);
    }

    private VBox goals(SimulationResult r, Formats f) {
        if (r.goals().isEmpty()) {
            return Widgets.section("Objectifs d'épargne", Widgets.emptyState(
                    "Aucun objectif en cours avec une échéance : rien à comparer."));
        }
        VBox list = new VBox(4);
        for (GoalImpact g : r.goals()) {
            list.getChildren().add(Widgets.label(g.name() + " : " + f.money(g.monthlyNeeded()) + " par mois jusqu'à "
                    + MONTH.format(g.targetDate()), "op-label"));
        }
        Money needed = r.goalsMonthlyNeeded();
        list.getChildren().add(coverage("Avant", r.before().savingCapacity(), needed, f));
        list.getChildren().add(coverage("Après", r.after().savingCapacity(), needed, f));
        return Widgets.section("Objectifs d'épargne", list);
    }

    private static Label coverage(String when, Money capacity, Money needed, Formats f) {
        Money gap = capacity.minus(needed);
        String text = when + " : capacité d'épargne " + f.money(capacity) + " / mois pour " + f.money(needed)
                + " / mois demandés par vos objectifs → "
                + (gap.isNegative() ? "⚠ il manquerait " + f.money(gap.negate()) + " / mois" : "✓ couvert (marge " + f.money(gap) + ")");
        return Widgets.label(text, "total-label");
    }

    // ---------------------------------------------------------------- outils

    private void open(Simulation s) {
        currentId = s.id();
        refresh();
    }

    private void save(Simulation s) {
        try {
            open(ctx.services().simulations().save(s));
        } catch (RuntimeException e) {
            Dialogs.error(window(), e);
        }
    }

    private static List<SimulationItem> append(List<SimulationItem> items, SimulationItem item) {
        List<SimulationItem> result = new ArrayList<>(items);
        result.add(item);
        return result;
    }

    private Optional<String> askName(String title, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText("Nom :");
        Dialogs.style(dialog.getDialogPane(), window(), dialog);
        return dialog.showAndWait().map(String::strip).filter(s -> !s.isEmpty());
    }

    private static Label right(Label l) {
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER_RIGHT);
        return l;
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
