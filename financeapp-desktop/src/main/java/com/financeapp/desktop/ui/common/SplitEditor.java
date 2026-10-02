package com.financeapp.desktop.ui.common;

import com.financeapp.core.service.BusinessException;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Editeur de ventilation (lignes categorie + montant) partage par la saisie des
 * operations et des recurrences. Le reste a repartir est affiche en texte
 * ("Reste a repartir", "Ventilation complete", "Depassement"), jamais par la
 * seule couleur.
 */
public final class SplitEditor {

    /** Ligne saisie : categorie ({@code null} = sans categorie) et montant positif. */
    public record Line(Long categoryId, BigDecimal amount) {
    }

    private record Row(HBox node, ComboBox<Choice<Long>> category, TextField amount) {
    }

    private final Function<Long, List<Choice<Long>>> choices;
    private final Supplier<String> totalText;
    private final Runnable onResize;
    private final VBox lines = new VBox(6);
    private final Label status = Widgets.label("", "op-detail");
    private final VBox root = new VBox(6);
    private final List<Row> rows = new ArrayList<>();

    /**
     * @param choices   categories proposees (le parametre est une categorie a garder meme archivee)
     * @param totalText texte du montant total saisi
     * @param onResize  appele quand la hauteur change (la fenetre s'ajuste)
     * @param onCancel  bouton "Ne pas ventiler"
     */
    public SplitEditor(Function<Long, List<Choice<Long>>> choices, Supplier<String> totalText, Runnable onResize,
                       Runnable onCancel) {
        this.choices = choices;
        this.totalText = totalText;
        this.onResize = onResize;
        Button addLine = new Button("+  Ajouter une ligne");
        addLine.getStyleClass().addAll("ghost", "compact");
        addLine.setOnAction(e -> addRow(null, ""));
        Button cancel = new Button("Ne pas ventiler");
        cancel.getStyleClass().addAll("ghost", "compact");
        cancel.setOnAction(e -> onCancel.run());
        root.getChildren().setAll(lines, Widgets.row(addLine, cancel, Widgets.spacer(), status));
        root.getStyleClass().add("split-editor");
        root.setVisible(false);
        root.managedProperty().bind(root.visibleProperty());
    }

    public Node node() {
        return root;
    }

    public boolean isActive() {
        return root.isVisible();
    }

    /** Ouvre l'editeur avec les lignes donnees. */
    public void open(List<Line> initial) {
        clear();
        root.setVisible(true);
        initial.forEach(l -> addRow(l.categoryId(), l.amount() == null ? "" : AmountParser.toEditable(l.amount())));
        if (!rows.isEmpty()) {
            rows.getLast().amount().requestFocus();
        }
        onResize.run();
    }

    /** Ferme l'editeur ; renvoie la categorie de la premiere ligne (pour revenir a une seule categorie). */
    public Long close() {
        Long first = rows.isEmpty() ? null : Widgets.selected(rows.getFirst().category());
        clear();
        root.setVisible(false);
        onResize.run();
        return first;
    }

    /** Recharge les categories proposees (changement de sens depense / revenu). */
    public void refreshChoices() {
        for (Row r : rows) {
            Long value = Widgets.selected(r.category());
            r.category().getItems().setAll(choices.apply(value));
            Widgets.select(r.category(), value);
        }
    }

    /** Montant total saisi modifie : le reste a repartir est recalcule. */
    public void totalChanged() {
        updateStatus();
    }

    /**
     * Lignes saisies (les lignes laissees vides sont ignorees).
     *
     * @throws BusinessException moins de deux lignes, ou montant invalide
     */
    public List<Line> lines() {
        List<Line> result = new ArrayList<>();
        for (Row r : rows) {
            if (r.amount().getText().isBlank()) {
                continue;
            }
            BigDecimal value = AmountParser.parse(r.amount().getText())
                    .orElseThrow(() -> new BusinessException("Montant de ventilation invalide : saisissez par exemple 90,00"));
            if (value.signum() <= 0) {
                throw new BusinessException("Chaque ligne de la ventilation doit avoir un montant positif");
            }
            result.add(new Line(Widgets.selected(r.category()), value));
        }
        if (result.size() < 2) {
            throw new BusinessException(
                    "Une ventilation comporte au moins deux lignes avec un montant (ou choisissez « Ne pas ventiler »)");
        }
        return result;
    }

    private void clear() {
        rows.clear();
        lines.getChildren().clear();
    }

    private void addRow(Long categoryId, String value) {
        ComboBox<Choice<Long>> combo = new ComboBox<>();
        combo.getItems().setAll(choices.apply(categoryId));
        Widgets.select(combo, categoryId);
        if (combo.getValue() == null) {
            combo.getSelectionModel().selectFirst();
        }
        combo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(combo, Priority.ALWAYS);
        TextField part = new TextField(value);
        part.setPromptText("0,00");
        part.setPrefColumnCount(8);
        part.textProperty().addListener((o, a, b) -> updateStatus());
        Button remove = new Button("✕");
        remove.getStyleClass().addAll("ghost", "compact");
        remove.setTooltip(new Tooltip("Retirer cette ligne"));
        HBox node = new HBox(8, combo, part, remove);
        node.setAlignment(Pos.CENTER_LEFT);
        Row row = new Row(node, combo, part);
        remove.setOnAction(e -> {
            rows.remove(row);
            lines.getChildren().remove(node);
            updateStatus();
            onResize.run();
        });
        rows.add(row);
        lines.getChildren().add(node);
        updateStatus();
        onResize.run();
    }

    private void updateStatus() {
        if (!root.isVisible()) {
            return;
        }
        BigDecimal total = AmountParser.parse(totalText.get()).map(BigDecimal::abs).orElse(BigDecimal.ZERO);
        BigDecimal sum = BigDecimal.ZERO;
        for (Row r : rows) {
            sum = sum.add(AmountParser.parse(r.amount().getText()).map(BigDecimal::abs).orElse(BigDecimal.ZERO));
        }
        BigDecimal gap = total.subtract(sum);
        status.getStyleClass().removeAll("amount-negative", "amount-positive");
        if (gap.signum() == 0 && total.signum() > 0) {
            status.setText("✓  Ventilation complète");
            status.getStyleClass().add("amount-positive");
        } else if (gap.signum() > 0) {
            status.setText("Reste à répartir : " + AmountParser.toEditable(gap));
        } else {
            status.setText("⚠  Dépassement : " + AmountParser.toEditable(gap.negate()));
            status.getStyleClass().add("amount-negative");
        }
    }
}
