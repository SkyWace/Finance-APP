package com.financeapp.desktop.ui.common;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/** Selecteur de mois : ◀ Octobre 2026 ▶ (+ retour au mois courant). */
public final class MonthPicker extends HBox {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Formats.LOCALE);

    private final ObjectProperty<YearMonth> month;

    public MonthPicker(YearMonth initial, YearMonth current) {
        super(6);
        month = new SimpleObjectProperty<>(initial);
        Button prev = new Button("◀");
        Button next = new Button("▶");
        Button today = new Button("Mois en cours");
        for (Button b : new Button[]{prev, next, today}) {
            b.getStyleClass().addAll("ghost", "compact");
        }
        Label label = Widgets.label("", "month-label");
        label.setMinWidth(150);
        label.setAlignment(Pos.CENTER);
        prev.setOnAction(e -> month.set(month.get().minusMonths(1)));
        next.setOnAction(e -> month.set(month.get().plusMonths(1)));
        today.setOnAction(e -> month.set(current));
        month.addListener((o, old, m) -> label.setText(Formats.capitalize(FORMAT.format(m))));
        label.setText(Formats.capitalize(FORMAT.format(initial)));
        today.visibleProperty().bind(month.isNotEqualTo(current));
        setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(prev, label, next, today);
    }

    public ObjectProperty<YearMonth> monthProperty() {
        return month;
    }

    public YearMonth month() {
        return month.get();
    }

    public static String format(YearMonth m) {
        return Formats.capitalize(FORMAT.format(m));
    }
}
