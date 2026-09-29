package com.financeapp.desktop.ui.common;

import com.financeapp.core.account.Account;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.money.Money;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fabriques de composants visuels partages (cartes, montants, badges...). */
public final class Widgets {

    private Widgets() {
    }

    public static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        return l;
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Montant signe, colore ET prefixe du signe (jamais la couleur seule). */
    public static Label amount(Formats formats, Money m, String... extraClasses) {
        Label l = new Label(formats.signed(m));
        l.getStyleClass().addAll("amount", Formats.signClass(m));
        l.getStyleClass().addAll(extraClasses);
        return l;
    }

    /** Carte d'indicateur : titre, valeur, sous-titre optionnel. */
    public static VBox kpiCard(String title, String value, String valueClass, String subtitle) {
        Label t = label(title.toUpperCase(Formats.LOCALE), "kpi-title");
        Label v = label(value, "kpi-value");
        if (valueClass != null) {
            v.getStyleClass().add(valueClass);
        }
        VBox card = new VBox(6, t, v);
        if (subtitle != null && !subtitle.isBlank()) {
            card.getChildren().add(label(subtitle, "kpi-subtitle"));
        }
        card.getStyleClass().add("card");
        card.setMinWidth(170);
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    public static VBox section(String title, Node... content) {
        VBox box = new VBox(10);
        box.getStyleClass().add("card");
        if (title != null) {
            box.getChildren().add(label(title, "section-title"));
        }
        box.getChildren().addAll(content);
        return box;
    }

    /** Ligne d'operation : date | libelle (+ detail) | badge optionnel | montant signe. */
    public static HBox operationRow(Formats formats, java.time.LocalDate date, String label, String detail,
                                    Money amount, Label badge) {
        Label d = label(Formats.shortDate(date), "op-date");
        d.setMinWidth(48);
        Label l = label(label, "op-label");
        VBox texts = new VBox(1, l);
        if (detail != null && !detail.isBlank()) {
            texts.getChildren().add(label(detail, "op-detail"));
        }
        HBox row = new HBox(12, d, texts, spacer());
        if (badge != null) {
            row.getChildren().add(badge);
        }
        row.getChildren().add(amount(formats, amount));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("op-row");
        return row;
    }

    public static Label badge(String text, String kind) {
        return label(text, "badge", "badge-" + kind);
    }

    public static Label emptyState(String text) {
        Label l = label(text, "empty-state");
        l.setWrapText(true);
        return l;
    }

    public static HBox row(Node... children) {
        HBox box = new HBox(10, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Liste deroulante des comptes actifs (et eventuellement du compte courant de l'operation, meme archive). */
    public static ComboBox<Choice<Long>> accountCombo(List<Account> accounts, Long selected) {
        ComboBox<Choice<Long>> combo = new ComboBox<>();
        for (Account a : accounts) {
            if (!a.archived() || Objects.equals(a.id(), selected)) {
                combo.getItems().add(new Choice<>(a.id(), a.name() + (a.archived() ? " (archivé)" : "")));
            }
        }
        select(combo, selected);
        if (combo.getSelectionModel().isEmpty() && !combo.getItems().isEmpty()) {
            combo.getSelectionModel().selectFirst();
        }
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    /** Categories compatibles avec le sens de l'operation, sous la forme "Categorie › Sous-categorie". */
    public static List<Choice<Long>> categoryChoices(Map<Category, List<Category>> tree, boolean income, Long keep) {
        List<Choice<Long>> items = new ArrayList<>();
        items.add(new Choice<>(null, "— Sans catégorie —"));
        for (var e : tree.entrySet()) {
            Category root = e.getKey();
            if (accepts(root.kind(), income) || Objects.equals(root.id(), keep)) {
                items.add(new Choice<>(root.id(), root.name()));
            }
            for (Category child : e.getValue()) {
                if (accepts(child.kind(), income) || Objects.equals(child.id(), keep)) {
                    items.add(new Choice<>(child.id(), root.name() + " › " + child.name()));
                }
            }
        }
        return items;
    }

    private static boolean accepts(CategoryKind kind, boolean income) {
        return income ? kind != CategoryKind.EXPENSE : kind != CategoryKind.INCOME;
    }

    private static final java.time.format.DateTimeFormatter PICKER_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Selecteur de date au format francais jj/mm/aaaa. */
    public static javafx.scene.control.DatePicker datePicker(java.time.LocalDate value) {
        javafx.scene.control.DatePicker picker = new javafx.scene.control.DatePicker(value);
        picker.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(java.time.LocalDate d) {
                return d == null ? "" : PICKER_FORMAT.format(d);
            }

            @Override
            public java.time.LocalDate fromString(String s) {
                if (s == null || s.isBlank()) {
                    return null;
                }
                try {
                    return java.time.LocalDate.parse(s.strip(), PICKER_FORMAT);
                } catch (java.time.format.DateTimeParseException e) {
                    return null;
                }
            }
        });
        picker.setPromptText("jj/mm/aaaa");
        picker.setMaxWidth(Double.MAX_VALUE);
        return picker;
    }

    /**
     * Valeur d'un selecteur de date, y compris quand l'utilisateur a tape la
     * date sans valider par Entree (le DatePicker ne la prend pas en compte seul).
     */
    public static java.time.LocalDate dateValue(javafx.scene.control.DatePicker picker) {
        String text = picker.getEditor().getText();
        if (text != null && !text.isBlank()) {
            java.time.LocalDate typed = picker.getConverter().fromString(text);
            if (typed != null) {
                picker.setValue(typed);
            }
            return typed;
        }
        return picker.getValue();
    }

    public static <T> void select(ComboBox<Choice<T>> combo, T value) {
        combo.getItems().stream().filter(c -> c.is(value)).findFirst().ifPresent(c -> combo.getSelectionModel().select(c));
    }

    public static <T> T selected(ComboBox<Choice<T>> combo) {
        Choice<T> c = combo.getValue();
        return c == null ? null : c.value();
    }
}
