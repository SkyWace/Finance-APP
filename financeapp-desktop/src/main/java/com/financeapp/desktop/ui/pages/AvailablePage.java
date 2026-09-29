package com.financeapp.desktop.ui.pages;

import com.financeapp.core.available.AvailableBalanceResult;
import com.financeapp.core.available.AvailableBalanceResult.Line;
import com.financeapp.core.available.AvailableBalanceResult.Section;
import com.financeapp.core.available.Horizon;
import com.financeapp.core.available.HorizonType;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiAsync;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;

/**
 * Le "disponible reel" et son explication ligne a ligne : chaque somme
 * deduite ou ajoutee est visible, rien n'est calcule en boite noire.
 */
public final class AvailablePage extends Page {

    private record Data(Horizon horizon, AvailableBalanceResult result) {
    }

    private final ComboBox<HorizonType> horizon = new ComboBox<>();
    private final DatePicker customDate = Widgets.datePicker(null);
    private final CheckBox includeIncome = new CheckBox("Compter les revenus prévus certains (salaire…)");
    private final VBox body = new VBox(18);

    public AvailablePage(UiContext ctx) {
        super(ctx);
        horizon.getItems().setAll(HorizonType.values());
        horizon.setValue(ctx.services().settings().defaultHorizon());
        customDate.setValue(ctx.services().planning().today().plusDays(14));
        customDate.setMaxWidth(160);
        customDate.visibleProperty().bind(horizon.valueProperty().isEqualTo(HorizonType.CUSTOM_DATE));
        customDate.managedProperty().bind(customDate.visibleProperty());
        includeIncome.setSelected(ctx.services().settings().includeCertainIncome());

        horizon.setOnAction(e -> {
            ctx.services().settings().setDefaultHorizon(horizon.getValue());
            refresh();
        });
        customDate.valueProperty().addListener((o, old, d) -> refresh());
        includeIncome.setOnAction(e -> {
            ctx.services().settings().setIncludeCertainIncome(includeIncome.isSelected());
            refresh();
        });
        HBox controls = Widgets.row(Widgets.label("Calculer mon disponible jusqu'à", "muted"), horizon, customDate,
                Widgets.spacer(), includeIncome);
        content.getChildren().setAll(controls, body);
    }

    @Override
    public String title() {
        return "Disponible réel";
    }

    @Override
    public void refresh() {
        HorizonType type = horizon.getValue();
        LocalDate custom = customDate.getValue();
        var service = ctx.services().available();
        UiAsync.load(() -> {
            Horizon h = service.resolve(type, custom);
            return new Data(h, service.compute(h));
        }, this::render);
    }

    private void render(Data data) {
        Formats f = ctx.formats();
        AvailableBalanceResult r = data.result();
        String until = "Jusqu'au " + Formats.longDate(r.horizonEnd()).toLowerCase(Formats.LOCALE);
        if (data.horizon().type() == HorizonType.NEXT_PAYDAY) {
            until = data.horizon().payday() != null
                    ? "Jusqu'à la veille de la prochaine paie (" + Formats.date(data.horizon().payday()) + ")"
                    : "Aucun revenu récurrent trouvé : calcul jusqu'à la fin du mois";
        }

        Label big = Widgets.label(f.money(r.available()), "hero-value",
                r.available().isNegative() ? "amount-negative" : "accent");
        Label caption = Widgets.label("RESTE RÉELLEMENT DISPONIBLE", "kpi-title");
        VBox hero = new VBox(6, caption, big, Widgets.label(until, "muted"));
        if (r.available().isNegative()) {
            hero.getChildren().add(Widgets.badge("⚠ Les opérations prévues dépassent le solde actuel", "danger"));
        }
        hero.getStyleClass().addAll("card", "hero");

        VBox explanation = new VBox(14);
        for (Section s : r.sections()) {
            explanation.getChildren().add(section(s, f));
        }
        Label total = Widgets.label(f.money(r.available()), "total-value", Formats.signClass(r.available()));
        HBox totalRow = Widgets.row(Widgets.label("= DISPONIBLE RÉEL", "total-label"), Widgets.spacer(), total);
        totalRow.getStyleClass().add("total-row");
        explanation.getChildren().add(totalRow);

        Label how = Widgets.label("Solde actuel des comptes inclus dans le disponible (effectué + en attente), "
                + "moins les dépenses prévues et récurrentes jusqu'à l'échéance (retards compris), moins les virements "
                + "prévus vers l'épargne, plus les revenus prévus jugés certains. Les virements entre deux comptes "
                + "inclus sont neutres.", "muted");
        how.setWrapText(true);
        body.getChildren().setAll(hero, Widgets.section("Détail du calcul", explanation), how);
    }

    private VBox section(Section s, Formats f) {
        Label title = Widgets.label(s.kind().title().toUpperCase(Formats.LOCALE), "explain-title");
        Label total = s.counted() ? Widgets.amount(f, s.total(), "explain-total")
                : Widgets.label("non compté · " + f.signed(s.total()), "muted");
        VBox box = new VBox(4, Widgets.row(title, Widgets.spacer(), total));
        for (Line line : s.lines()) {
            Label date = Widgets.label(line.date() == null ? "" : Formats.shortDate(line.date()), "op-date");
            date.setMinWidth(48);
            HBox row = new HBox(12, date, Widgets.label(line.label(), "explain-line"), Widgets.spacer());
            if (line.overdue()) {
                row.getChildren().add(Widgets.badge("en retard", "warning"));
            }
            row.getChildren().add(Widgets.amount(f, line.amount()));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("explain-row");
            box.getChildren().add(row);
        }
        if (s.lines().isEmpty()) {
            box.getChildren().add(Widgets.emptyState("Aucun compte inclus dans le disponible."));
        }
        box.getStyleClass().add("explain-section");
        return box;
    }
}
