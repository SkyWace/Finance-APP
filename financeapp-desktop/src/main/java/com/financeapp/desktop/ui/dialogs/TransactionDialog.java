package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Saisie d'un revenu ou d'une depense. Le statut propose suit la date
 * (futur = prevu, passe = effectue) tant que l'utilisateur ne l'a pas choisi.
 * L'operation peut etre ventilee sur plusieurs categories ("Ventiler...") et
 * porter des etiquettes, saisies librement et creees a la volee.
 */
public final class TransactionDialog extends FormDialog<Transaction> {

    private final Transaction existing;
    private final ToggleButton expenseToggle = new ToggleButton("Dépense");
    private final ToggleButton incomeToggle = new ToggleButton("Revenu");
    private final ComboBox<Choice<Long>> account;
    private final DatePicker date;
    private final TextField label = new TextField();
    private final TextField amount = new TextField();
    private final ComboBox<Choice<Long>> category = new ComboBox<>();
    private final ComboBox<TransactionStatus> status = new ComboBox<>();
    private final TextArea note = new TextArea();
    private final Button splitButton = new Button("Ventiler…");
    private final VBox splitEditor = new VBox(6);
    private final VBox splitLines = new VBox(6);
    private final Label splitStatus = Widgets.label("", "op-detail");
    private final List<SplitRow> rows = new ArrayList<>();
    private final TextField tags = new TextField();
    private final FlowPane tagSuggestions = new FlowPane(6, 6);
    private boolean statusTouched;

    /** Ligne de l'editeur de ventilation. */
    private record SplitRow(HBox node, ComboBox<Choice<Long>> category, TextField amount) {
    }

    public TransactionDialog(UiContext ctx, Transaction existing, TransactionType initialType, Long accountId) {
        super(ctx, existing == null ? "Nouvelle opération" : "Modifier l'opération", "Enregistrer");
        this.existing = existing;
        LocalDate today = ctx.services().planning().today();

        ToggleGroup group = new ToggleGroup();
        expenseToggle.setToggleGroup(group);
        incomeToggle.setToggleGroup(group);
        expenseToggle.getStyleClass().add("segment");
        incomeToggle.getStyleClass().add("segment");
        group.selectedToggleProperty().addListener((o, old, t) -> {
            if (t == null) {
                old.setSelected(true);
            } else {
                refreshCategories();
            }
        });

        account = Widgets.accountCombo(ctx.services().accounts().findAll(),
                existing != null ? Long.valueOf(existing.accountId()) : accountId);
        date = Widgets.datePicker(existing != null ? existing.date() : today);
        status.getItems().setAll(TransactionStatus.values());
        status.setMaxWidth(Double.MAX_VALUE);
        category.setMaxWidth(Double.MAX_VALUE);
        note.setPrefRowCount(2);
        note.setWrapText(true);
        label.setPromptText("Ex. Carrefour");
        amount.setPromptText("0,00");

        if (existing != null) {
            (existing.type() == TransactionType.INCOME ? incomeToggle : expenseToggle).setSelected(true);
            label.setText(existing.label());
            amount.setText(AmountParser.toEditable(existing.amount().abs().amount()));
            status.setValue(existing.status());
            note.setText(existing.note());
            statusTouched = true;
        } else {
            (initialType == TransactionType.INCOME ? incomeToggle : expenseToggle).setSelected(true);
            status.setValue(TransactionStatus.COMPLETED);
        }
        refreshCategories();
        if (existing != null) {
            Widgets.select(category, existing.categoryId());
        }

        // Ventilation
        splitButton.getStyleClass().addAll("ghost", "compact");
        splitButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        splitButton.setTooltip(new javafx.scene.control.Tooltip(
                "Répartir le montant sur plusieurs catégories (ex. courses 90 € + maison 30 €)"));
        splitButton.setOnAction(e -> startSplit());
        HBox.setHgrow(category, Priority.ALWAYS);
        Button addLine = new Button("+  Ajouter une ligne");
        addLine.getStyleClass().addAll("ghost", "compact");
        addLine.setOnAction(e -> addSplitRow(null, ""));
        Button cancelSplit = new Button("Ne pas ventiler");
        cancelSplit.getStyleClass().addAll("ghost", "compact");
        cancelSplit.setOnAction(e -> stopSplit());
        splitEditor.getChildren().setAll(splitLines, Widgets.row(addLine, cancelSplit, Widgets.spacer(), splitStatus));
        splitEditor.getStyleClass().add("split-editor");
        splitEditor.setVisible(false);
        splitEditor.managedProperty().bind(splitEditor.visibleProperty());
        amount.textProperty().addListener((o, a, b) -> updateSplitStatus());
        if (existing != null && existing.isSplit()) {
            Widgets.select(category, null);
            showSplitEditor(true);
            existing.splits().forEach(l -> addSplitRow(l.categoryId(), AmountParser.toEditable(l.amount().abs().amount())));
        }

        // Etiquettes
        tags.setPromptText("Ex. vacances 2026, remboursable");
        Map<Long, String> tagNames = ctx.services().tags().names();
        if (existing != null) {
            tags.setText(String.join(", ", existing.tagIds().stream().map(tagNames::get)
                    .filter(java.util.Objects::nonNull).sorted(String.CASE_INSENSITIVE_ORDER).toList()));
        }
        tags.textProperty().addListener((o, a, b) -> refreshTagSuggestions());
        refreshTagSuggestions();

        status.setOnAction(e -> statusTouched = true);
        date.valueProperty().addListener((o, old, d) -> {
            if (!statusTouched && d != null) {
                status.setValue(d.isAfter(today) ? TransactionStatus.PLANNED : TransactionStatus.COMPLETED);
            }
        });

        addRow("Type", new HBox(0, expenseToggle, incomeToggle));
        addRow("Compte", account);
        addRow("Date", date);
        addRow("Libellé", label);
        addRow("Montant", amount);
        addRow("Catégorie", new HBox(8, category, splitButton));
        addFullRow(splitEditor);
        addRow("Statut", status);
        addRow("Étiquettes", new VBox(6, tags, tagSuggestions));
        addRow("Commentaire", note);
        setOnShown(e -> (existing == null ? amount : label).requestFocus());
    }

    private void refreshCategories() {
        Long current = Widgets.selected(category);
        category.getItems().setAll(categoryChoices(existing != null ? existing.categoryId() : null));
        Widgets.select(category, current);
        if (category.getValue() == null) {
            category.getSelectionModel().selectFirst();
        }
        for (SplitRow r : rows) {
            Long value = Widgets.selected(r.category());
            r.category().getItems().setAll(categoryChoices(value));
            Widgets.select(r.category(), value);
        }
    }

    private List<Choice<Long>> categoryChoices(Long keep) {
        return Widgets.categoryChoices(ctx.services().categories().activeTree(), incomeToggle.isSelected(), keep);
    }

    // ------------------------------------------------------------ ventilation

    /** Passe en ventilation : la categorie choisie garde tout le montant, une ligne vide est ajoutee. */
    private void startSplit() {
        Long current = Widgets.selected(category);
        showSplitEditor(true);
        addSplitRow(current, amount.getText());
        addSplitRow(null, "");
        rows.getLast().amount().requestFocus();
    }

    /** Retour a une seule categorie : celle de la premiere ligne. */
    private void stopSplit() {
        Long first = rows.isEmpty() ? null : Widgets.selected(rows.getFirst().category());
        rows.clear();
        splitLines.getChildren().clear();
        showSplitEditor(false);
        Widgets.select(category, first);
    }

    private void showSplitEditor(boolean on) {
        splitEditor.setVisible(on);
        category.setVisible(!on);
        category.setManaged(!on);
        splitButton.setVisible(!on);
        splitButton.setManaged(!on);
        fitToContent();
    }

    private void addSplitRow(Long categoryId, String value) {
        ComboBox<Choice<Long>> combo = new ComboBox<>();
        combo.getItems().setAll(categoryChoices(categoryId));
        Widgets.select(combo, categoryId);
        if (combo.getValue() == null) {
            combo.getSelectionModel().selectFirst();
        }
        combo.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(combo, Priority.ALWAYS);
        TextField part = new TextField(value);
        part.setPromptText("0,00");
        part.setPrefColumnCount(8);
        part.textProperty().addListener((o, a, b) -> updateSplitStatus());
        Button remove = new Button("✕");
        remove.getStyleClass().addAll("ghost", "compact");
        remove.setTooltip(new javafx.scene.control.Tooltip("Retirer cette ligne"));
        HBox node = new HBox(8, combo, part, remove);
        node.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        SplitRow row = new SplitRow(node, combo, part);
        remove.setOnAction(e -> {
            rows.remove(row);
            splitLines.getChildren().remove(node);
            updateSplitStatus();
            fitToContent();
        });
        rows.add(row);
        splitLines.getChildren().add(node);
        updateSplitStatus();
        fitToContent();
    }

    /** "Reste a repartir", "Ventilation complete" ou "Depassement", en texte (pas seulement en couleur). */
    private void updateSplitStatus() {
        if (!splitEditor.isVisible()) {
            return;
        }
        BigDecimal total = AmountParser.parse(amount.getText()).map(BigDecimal::abs).orElse(BigDecimal.ZERO);
        BigDecimal sum = BigDecimal.ZERO;
        for (SplitRow r : rows) {
            sum = sum.add(AmountParser.parse(r.amount().getText()).map(BigDecimal::abs).orElse(BigDecimal.ZERO));
        }
        BigDecimal gap = total.subtract(sum);
        splitStatus.getStyleClass().removeAll("amount-negative", "amount-positive");
        if (gap.signum() == 0 && total.signum() > 0) {
            splitStatus.setText("✓  Ventilation complète");
            splitStatus.getStyleClass().add("amount-positive");
        } else if (gap.signum() > 0) {
            splitStatus.setText("Reste à répartir : " + AmountParser.toEditable(gap));
        } else {
            splitStatus.setText("⚠  Dépassement : " + AmountParser.toEditable(gap.negate()));
            splitStatus.getStyleClass().add("amount-negative");
        }
    }

    // -------------------------------------------------------------- etiquettes

    /** Etiquettes existantes pas encore saisies, cliquables pour les ajouter. */
    private void refreshTagSuggestions() {
        Set<String> typed = new java.util.HashSet<>(typedTags().stream().map(t -> t.toLowerCase(java.util.Locale.ROOT)).toList());
        tagSuggestions.getChildren().clear();
        ctx.services().tags().findAll().stream()
                .filter(t -> !typed.contains(t.name().toLowerCase(java.util.Locale.ROOT)))
                .limit(10)
                .forEach(t -> {
                    Button b = new Button("+ " + t.name());
                    b.getStyleClass().addAll("ghost", "compact", "tag-suggestion");
                    b.setOnAction(e -> {
                        String text = tags.getText().strip();
                        tags.setText(text.isEmpty() || text.endsWith(",") ? text + (text.isEmpty() ? "" : " ") + t.name()
                                : text + ", " + t.name());
                    });
                    tagSuggestions.getChildren().add(b);
                });
        tagSuggestions.setVisible(!tagSuggestions.getChildren().isEmpty());
        tagSuggestions.setManaged(tagSuggestions.isVisible());
    }

    private List<String> typedTags() {
        return Arrays.stream(tags.getText().split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();
    }

    @Override
    protected Transaction submit() {
        if (label.getText() == null || label.getText().isBlank()) {
            throw new com.financeapp.core.service.BusinessException("Le libellé est obligatoire");
        }
        List<TransactionDraft.Split> splits = new ArrayList<>();
        if (splitEditor.isVisible()) {
            for (SplitRow r : rows) {
                if (r.amount().getText().isBlank()) {
                    continue; // ligne laissee vide
                }
                splits.add(new TransactionDraft.Split(Widgets.selected(r.category()), requireAmount(r.amount(), false)));
            }
            if (splits.size() < 2) {
                throw new com.financeapp.core.service.BusinessException(
                        "Une ventilation comporte au moins deux lignes avec un montant (ou choisissez « Ne pas ventiler »)");
            }
        }
        Set<Long> tagIds = ctx.services().tags().resolve(typedTags());
        TransactionDraft draft = new TransactionDraft(
                require(Widgets.selected(account), "Choisissez un compte"),
                require(Widgets.dateValue(date), "Date invalide"),
                label.getText(),
                requireAmount(amount, false),
                incomeToggle.isSelected() ? TransactionType.INCOME : TransactionType.EXPENSE,
                status.getValue(),
                splits.isEmpty() ? Widgets.selected(category) : null,
                note.getText(),
                splits,
                tagIds);
        if (draft.label() == null || draft.label().isBlank()) {
            throw new com.financeapp.core.service.BusinessException("Le libellé est obligatoire");
        }
        return existing == null
                ? ctx.services().transactions().create(draft)
                : ctx.services().transactions().update(existing.id(), draft);
    }
}
