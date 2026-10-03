package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.SplitEditor;
import com.financeapp.desktop.ui.common.TagField;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
    private final SplitEditor splitEditor;
    private final TagField tags;
    private final com.financeapp.desktop.ui.common.AttachmentsPane attachments;
    private boolean statusTouched;


    public TransactionDialog(UiContext ctx, Transaction existing, TransactionType initialType, Long accountId) {
        super(ctx, existing == null ? "Nouvelle opération" : "Modifier l'opération", "Enregistrer");
        this.existing = existing;
        LocalDate today = ctx.services().planning().today();
        splitEditor = new SplitEditor(this::categoryChoices, amount::getText, this::fitToContent, this::stopSplit);

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
        amount.textProperty().addListener((o, a, b) -> splitEditor.totalChanged());
        if (existing != null && existing.isSplit()) {
            Widgets.select(category, null);
            showSplit(existing.splits().stream()
                    .map(l -> new SplitEditor.Line(l.categoryId(), l.amount().abs().amount())).toList());
        }

        // Etiquettes
        tags = new TagField(ctx.services().tags());
        if (existing != null) {
            tags.setTagIds(existing.tagIds());
        }

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
        addFullRow(splitEditor.node());
        addRow("Statut", status);
        addRow("Étiquettes", tags.node());
        addRow("Commentaire", note);
        attachments = new com.financeapp.desktop.ui.common.AttachmentsPane(ctx,
                existing == null ? null : existing.id(), () -> getDialogPane().getScene().getWindow());
        attachments.setOnResize(this::fitToContent);
        addRow("Justificatifs", attachments.node());
        setOnShown(e -> (existing == null ? amount : label).requestFocus());
    }

    private void refreshCategories() {
        Long current = Widgets.selected(category);
        category.getItems().setAll(categoryChoices(existing != null ? existing.categoryId() : null));
        Widgets.select(category, current);
        if (category.getValue() == null) {
            category.getSelectionModel().selectFirst();
        }
        if (splitEditor != null) {
            splitEditor.refreshChoices();
        }
    }

    private List<Choice<Long>> categoryChoices(Long keep) {
        return Widgets.categoryChoices(ctx.services().categories().activeTree(), incomeToggle.isSelected(), keep);
    }

    // ------------------------------------------------------------ ventilation

    /** Passe en ventilation : la categorie choisie garde tout le montant, une ligne vide est ajoutee. */
    private void startSplit() {
        BigDecimal total = AmountParser.parse(amount.getText()).map(BigDecimal::abs).orElse(null);
        showSplit(List.of(new SplitEditor.Line(Widgets.selected(category), total), new SplitEditor.Line(null, null)));
    }

    private void showSplit(List<SplitEditor.Line> lines) {
        category.setVisible(false);
        category.setManaged(false);
        splitButton.setVisible(false);
        splitButton.setManaged(false);
        splitEditor.open(lines);
    }

    /** Retour a une seule categorie : celle de la premiere ligne. */
    private void stopSplit() {
        Long first = splitEditor.close();
        category.setVisible(true);
        category.setManaged(true);
        splitButton.setVisible(true);
        splitButton.setManaged(true);
        Widgets.select(category, first);
    }

    @Override
    protected Transaction submit() {
        if (label.getText() == null || label.getText().isBlank()) {
            throw new com.financeapp.core.service.BusinessException("Le libellé est obligatoire");
        }
        List<TransactionDraft.Split> splits = splitEditor.isActive()
                ? splitEditor.lines().stream().map(l -> new TransactionDraft.Split(l.categoryId(), l.amount())).toList()
                : List.of();
        Set<Long> tagIds = tags.resolve();
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
        Transaction saved = existing == null
                ? ctx.services().transactions().create(draft)
                : ctx.services().transactions().update(existing.id(), draft);
        try {
            attachments.commit(saved.id());
        } catch (RuntimeException e) {
            if (existing == null) {
                ctx.services().transactions().delete(saved.id()); // pas d'operation a moitie enregistree
            }
            throw e;
        }
        return saved;
    }
}
