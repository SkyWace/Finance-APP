package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.imports.CsvMapping;
import com.financeapp.core.imports.CsvMappingGuesser;
import com.financeapp.core.imports.CsvTable;
import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportCandidate;
import com.financeapp.core.imports.ImportedRow;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.ImportService;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Assistant d'import de releve bancaire :
 * <ol>
 *   <li>fichier (CSV, OFX, QIF) et compte ;</li>
 *   <li>correspondance des colonnes (CSV uniquement), avec apercu du fichier brut ;</li>
 *   <li>apercu ligne par ligne : statut (nouvelle, doublon, rapprochement...), categorie
 *       proposee et case "importer". Les cas ambigus sont decoches par defaut.</li>
 * </ol>
 * Rien n'est enregistre avant le clic sur "Importer".
 */
public final class ImportWizard extends Dialog<ImportBatch> {

    /** Ligne de l'apercu, modifiable par l'utilisateur. */
    private static final class PreviewRow {
        final ImportCandidate candidate;
        final SimpleBooleanProperty include;
        final SimpleObjectProperty<Choice<Long>> category;

        PreviewRow(ImportCandidate candidate, Choice<Long> category) {
            this.candidate = candidate;
            this.include = new SimpleBooleanProperty(candidate.includedByDefault());
            this.category = new SimpleObjectProperty<>(category);
        }
    }

    private final UiContext ctx;
    private final ButtonType back = new ButtonType("Précédent", ButtonBar.ButtonData.BACK_PREVIOUS);
    private final ButtonType next = new ButtonType("Suivant", ButtonBar.ButtonData.NEXT_FORWARD);
    private final ButtonType importButton = new ButtonType("Importer", ButtonBar.ButtonData.OK_DONE);
    private final VBox body = new VBox(12);
    private final Label error = Widgets.label("", "form-error");

    private int step = 1;
    private File file;
    private byte[] content;
    private ImportService.Format format;
    private final ComboBox<Choice<Long>> account;
    private CsvTable table;
    private List<ImportedRow> rows = List.of();
    private final ObservableList<PreviewRow> preview = FXCollections.observableArrayList();
    private ImportBatch result;
    /** Operations issues d'une synchronisation bancaire (l'assistant s'ouvre alors directement sur l'apercu). */
    private com.financeapp.core.service.BankSyncService.SyncBatch bankBatch;

    // Controles de correspondance (etape 2)
    private final Spinner<Integer> headerRows = new Spinner<>(0, 50, 0);
    private final ComboBox<Choice<Integer>> dateColumn = new ComboBox<>();
    private final ComboBox<String> datePattern = new ComboBox<>();
    private final ComboBox<Choice<Integer>> labelColumn = new ComboBox<>();
    private final ComboBox<Choice<Integer>> detailColumn = new ComboBox<>();
    private final ComboBox<CsvMapping.AmountMode> amountMode = new ComboBox<>();
    private final ComboBox<Choice<Integer>> amountColumn = new ComboBox<>();
    private final ComboBox<Choice<Integer>> debitColumn = new ComboBox<>();
    private final ComboBox<Choice<Integer>> creditColumn = new ComboBox<>();
    private final Label amountLabel = Widgets.label("Montant", "form-label");
    private final Label creditLabel = Widgets.label("Crédit", "form-label");
    private final Label mappingCheck = Widgets.label("", "op-detail");

    public ImportWizard(UiContext ctx) {
        this.ctx = ctx;
        setTitle("Importer un relevé bancaire");
        setHeaderText(null);
        Dialogs.style(getDialogPane(), ctx.window(), this);
        getDialogPane().getStyleClass().add("form-dialog");
        getDialogPane().setPrefSize(1060, 680);
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(back, next, importButton,
                new ButtonType("Annuler", ButtonBar.ButtonData.CANCEL_CLOSE));
        account = Widgets.accountCombo(ctx.services().accounts().findAll(), null);
        error.setWrapText(true);
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        VBox.setVgrow(body, Priority.ALWAYS);
        getDialogPane().setContent(new VBox(12, body, error));

        getDialogPane().lookupButton(next).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            guarded(this::goNext);
        });
        getDialogPane().lookupButton(back).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            step = step == 3 && format == ImportService.Format.CSV ? 2 : 1;
            render();
        });
        getDialogPane().lookupButton(importButton).addEventFilter(ActionEvent.ACTION, e -> {
            try {
                result = commit();
            } catch (RuntimeException ex) {
                e.consume();
                if (ex instanceof com.financeapp.core.service.BusinessException) {
                    error.setText(ex.getMessage());
                } else {
                    Dialogs.error(getDialogPane().getScene().getWindow(), ex);
                }
            }
        });
        setResultConverter(b -> b == importButton ? result : null);
        for (var combo : List.of(dateColumn, labelColumn, detailColumn, amountColumn, debitColumn, creditColumn)) {
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.setOnAction(e -> checkMapping());
        }
        datePattern.getItems().setAll(CsvMappingGuesser.DATE_PATTERNS);
        datePattern.setOnAction(e -> checkMapping());
        amountMode.getItems().setAll(CsvMapping.AmountMode.values());
        amountMode.setOnAction(e -> {
            updateAmountVisibility();
            checkMapping();
        });
        headerRows.valueProperty().addListener((o, old, v) -> checkMapping());
        render();
    }

    /**
     * Apercu des operations recuperees par synchronisation bancaire : meme
     * verification ligne a ligne qu'un import de fichier, compte impose.
     */
    public static ImportWizard forBankSync(UiContext ctx, com.financeapp.core.service.BankSyncService.SyncBatch batch) {
        ImportWizard wizard = new ImportWizard(ctx);
        wizard.setTitle("Vérifier les opérations synchronisées");
        wizard.bankBatch = batch;
        Widgets.select(wizard.account, batch.accountId());
        wizard.account.setDisable(true);
        wizard.rows = batch.rows();
        wizard.fillPreview(batch.candidates());
        wizard.step = 3;
        wizard.render();
        return wizard;
    }

    private void guarded(Runnable action) {
        try {
            error.setText("");
            action.run();
        } catch (com.financeapp.core.service.BusinessException | IllegalArgumentException ex) {
            error.setText(ex.getMessage());
        } catch (RuntimeException ex) {
            Dialogs.error(getDialogPane().getScene().getWindow(), ex);
        }
    }

    private void goNext() {
        if (step == 1) {
            if (file == null) {
                throw new IllegalArgumentException("Choisissez un fichier à importer.");
            }
            if (Widgets.selected(account) == null) {
                throw new IllegalArgumentException("Choisissez le compte concerné.");
            }
            if (format == ImportService.Format.CSV) {
                table = ctx.services().imports().readCsv(content);
                applyMapping(ctx.services().imports().guessMapping(table));
                step = 2;
            } else {
                rows = format == ImportService.Format.OFX
                        ? ctx.services().imports().parseOfx(content) : ctx.services().imports().parseQif(content);
                buildPreview();
                step = 3;
            }
        } else if (step == 2) {
            rows = ctx.services().imports().convertCsv(table, currentMapping());
            buildPreview();
            step = 3;
        }
        render();
    }

    private void render() {
        getDialogPane().lookupButton(back).setDisable(step == 1);
        getDialogPane().lookupButton(back).setVisible(bankBatch == null);
        getDialogPane().lookupButton(back).setManaged(bankBatch == null);
        getDialogPane().lookupButton(next).setVisible(step < 3);
        getDialogPane().lookupButton(next).setManaged(step < 3);
        getDialogPane().lookupButton(importButton).setVisible(step == 3);
        getDialogPane().lookupButton(importButton).setManaged(step == 3);
        Label title = Widgets.label(switch (step) {
            case 1 -> "Étape 1 sur 3 — Fichier et compte";
            case 2 -> "Étape 2 sur 3 — Correspondance des colonnes";
            default -> bankBatch != null ? bankBatch.sourceName() + " — vérification avant import"
                    : "Étape 3 sur 3 — Vérification avant import";
        }, "section-title");
        Node content = switch (step) {
            case 1 -> stepSource();
            case 2 -> stepMapping();
            default -> stepPreview();
        };
        VBox.setVgrow(content, Priority.ALWAYS);
        body.getChildren().setAll(title, content);
    }

    // ---------------------------------------------------------------- etape 1

    private Node stepSource() {
        Label chosen = Widgets.label(file == null ? "Aucun fichier choisi" : file.getName() + " — format " + format, "op-label");
        Button choose = new Button("Choisir un fichier…");
        choose.getStyleClass().add("secondary");
        choose.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Relevé à importer");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Relevés (*.csv, *.ofx, *.qfx, *.qif, *.txt)", "*.csv", "*.ofx", "*.qfx", "*.qif", "*.txt"),
                    new FileChooser.ExtensionFilter("Tous les fichiers", "*.*"));
            File f = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
            if (f != null) {
                guarded(() -> load(f));
                render();
            }
        });
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.addRow(0, Widgets.label("Fichier", "form-label"), Widgets.row(choose, chosen));
        grid.addRow(1, Widgets.label("Compte", "form-label"), account);
        account.setPrefWidth(320);
        Label help = Widgets.label("""
                Téléchargez le relevé depuis l'espace en ligne de votre banque (format CSV, OFX ou QIF), \
                puis choisissez-le ici. Le fichier est lu localement : rien n'est envoyé nulle part. \
                À l'étape suivante, vous verrez chaque ligne avant import : les doublons et les cas ambigus \
                ne sont jamais importés sans votre accord.""", "muted");
        help.setWrapText(true);
        return new VBox(18, grid, help);
    }

    private void load(File f) {
        try {
            content = Files.readAllBytes(f.toPath());
        } catch (IOException e) {
            throw new IllegalArgumentException("Fichier illisible : " + e.getMessage());
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("Le fichier est vide.");
        }
        if (content.length > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("Fichier trop volumineux pour un relevé (plus de 20 Mo).");
        }
        file = f;
        format = ImportService.detectFormat(f.getName(), content);
    }

    // ---------------------------------------------------------------- etape 2

    private Node stepMapping() {
        TableView<List<String>> raw = new TableView<>();
        raw.setPrefHeight(230);
        for (int c = 0; c < table.columnCount(); c++) {
            final int col = c;
            TableColumn<List<String>, String> column = new TableColumn<>("Colonne " + (c + 1));
            column.setCellValueFactory(r -> new SimpleStringProperty(col < r.getValue().size() ? r.getValue().get(col) : ""));
            column.setPrefWidth(150);
            raw.getColumns().add(column);
        }
        raw.getItems().setAll(table.rows().subList(0, Math.min(12, table.rows().size())));
        raw.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(List<String> item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("row-cancelled");
                if (!empty && getIndex() < headerRows.getValue()) {
                    getStyleClass().add("row-cancelled");
                }
            }
        });
        headerRows.valueProperty().addListener((o, old, v) -> raw.refresh());

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(8);
        int r = 0;
        grid.addRow(r++, Widgets.label("Lignes d'en-tête à ignorer", "form-label"), headerRows);
        grid.addRow(r++, Widgets.label("Date", "form-label"), dateColumn, Widgets.label("Format", "form-label"), datePattern);
        grid.addRow(r++, Widgets.label("Libellé", "form-label"), labelColumn, Widgets.label("Complément", "form-label"), detailColumn);
        grid.addRow(r++, Widgets.label("Montants", "form-label"), amountMode);
        grid.addRow(r, amountLabel, amountStack(), creditLabel, creditColumn);
        grid.getColumnConstraints().setAll(labelColumnConstraint(), new javafx.scene.layout.ColumnConstraints(),
                labelColumnConstraint());
        Label info = Widgets.label("Séparateur détecté : « " + (table.delimiter() == '\t' ? "tabulation" : table.delimiter())
                + " » · encodage " + table.charset() + " · les lignes barrées sont ignorées.", "muted");
        updateAmountVisibility();
        checkMapping();
        return new VBox(12, raw, info, grid, mappingCheck);
    }

    private void applyMapping(CsvMapping m) {
        List<Choice<Integer>> columns = new ArrayList<>();
        for (int c = 0; c < table.columnCount(); c++) {
            String header = m.headerRows() > 0 ? table.cell(m.headerRows() - 1, c) : "";
            columns.add(new Choice<>(c, "Colonne " + (c + 1) + (header.isBlank() ? "" : " — " + header)));
        }
        List<Choice<Integer>> optional = new ArrayList<>();
        optional.add(new Choice<>(null, "— Aucune —"));
        optional.addAll(columns);
        for (var combo : List.of(dateColumn, labelColumn, amountColumn, debitColumn, creditColumn)) {
            combo.getItems().setAll(columns);
        }
        detailColumn.getItems().setAll(optional);
        headerRows.getValueFactory().setValue(m.headerRows());
        Widgets.select(dateColumn, m.dateColumn());
        datePattern.setValue(m.datePattern());
        Widgets.select(labelColumn, m.labelColumn());
        Widgets.select(detailColumn, m.detailColumn());
        amountMode.setValue(m.mode());
        Widgets.select(amountColumn, m.amountColumn() == null ? 0 : m.amountColumn());
        Widgets.select(debitColumn, m.debitColumn() == null ? 0 : m.debitColumn());
        Widgets.select(creditColumn, m.creditColumn() == null ? 0 : m.creditColumn());
    }

    private void updateAmountVisibility() {
        boolean split = amountMode.getValue() == CsvMapping.AmountMode.DEBIT_CREDIT;
        amountColumn.setVisible(!split);
        debitColumn.setVisible(split);
        amountLabel.setText(split ? "Débit" : "Montant");
        creditLabel.setVisible(split);
        creditColumn.setVisible(split);
    }

    private Node amountStack() {
        javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(amountColumn, debitColumn);
        stack.setAlignment(Pos.CENTER_LEFT);
        return stack;
    }

    private static javafx.scene.layout.ColumnConstraints labelColumnConstraint() {
        javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
        c.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return c;
    }

    private CsvMapping currentMapping() {
        boolean split = amountMode.getValue() == CsvMapping.AmountMode.DEBIT_CREDIT;
        return new CsvMapping(headerRows.getValue(), required(dateColumn, "date"), datePattern.getValue(),
                required(labelColumn, "libellé"), Widgets.selected(detailColumn), amountMode.getValue(),
                split ? null : required(amountColumn, "montant"),
                split ? required(debitColumn, "débit") : null, split ? required(creditColumn, "crédit") : null);
    }

    private static Integer required(ComboBox<Choice<Integer>> combo, String what) {
        Integer v = Widgets.selected(combo);
        if (v == null) {
            throw new IllegalArgumentException("Choisissez la colonne " + what + ".");
        }
        return v;
    }

    private void checkMapping() {
        if (table == null || amountMode.getValue() == null || datePattern.getValue() == null) {
            return;
        }
        try {
            List<ImportedRow> converted = ctx.services().imports().convertCsv(table, currentMapping());
            long invalid = converted.stream().filter(r -> !r.isValid()).count();
            ImportedRow firstBad = converted.stream().filter(r -> !r.isValid()).findFirst().orElse(null);
            mappingCheck.setText(converted.size() + " ligne(s) lue(s), " + invalid + " illisible(s)"
                    + (firstBad == null ? "" : " — ex. ligne " + firstBad.line() + " : " + firstBad.error()));
        } catch (RuntimeException e) {
            mappingCheck.setText(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- etape 3

    private void buildPreview() {
        long accountId = Widgets.selected(account);
        fillPreview(ctx.services().imports().plan(accountId, rows));
    }

    private void fillPreview(List<ImportCandidate> candidates) {
        Map<Long, String> names = ctx.services().categories().fullNames();
        preview.clear();
        for (ImportCandidate c : candidates) {
            Long cat = c.suggestion() == null ? null : c.suggestion().categoryId();
            preview.add(new PreviewRow(c, cat == null ? NONE : new Choice<>(cat, names.getOrDefault(cat, "?"))));
        }
    }

    private static final Choice<Long> NONE = new Choice<>(null, "— Sans catégorie —");

    private Node stepPreview() {
        Formats f = ctx.formats();
        TableView<PreviewRow> view = new TableView<>(preview);
        view.setEditable(true);
        view.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<PreviewRow, Boolean> include = new TableColumn<>("Importer");
        include.setCellValueFactory(r -> r.getValue().include);
        include.setCellFactory(col -> new CheckBoxTableCell<>() {
            @Override
            public void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                PreviewRow row = getTableRow() == null ? null : getTableRow().getItem();
                setDisable(row != null && row.candidate.kind() == ImportCandidate.Kind.INVALID);
            }
        });
        include.setEditable(true);
        include.setPrefWidth(70);
        include.setMaxWidth(80);

        TableColumn<PreviewRow, String> date = new TableColumn<>("Date");
        date.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().candidate.row().date() == null
                ? "?" : Formats.date(r.getValue().candidate.row().date())));
        date.setMaxWidth(110);

        TableColumn<PreviewRow, String> label = new TableColumn<>("Libellé");
        label.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().candidate.row().label()));
        label.setPrefWidth(260);

        TableColumn<PreviewRow, String> amount = new TableColumn<>("Montant");
        amount.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().candidate.row().amount() == null ? ""
                : f.signed(Money.of(r.getValue().candidate.row().amount(),
                ctx.services().accounts().get(Widgets.selected(account)).currency()))));
        amount.setMaxWidth(130);
        amount.getStyleClass().add("amount-column");

        TableColumn<PreviewRow, String> status = new TableColumn<>("Statut");
        status.setCellValueFactory(r -> new SimpleStringProperty(describe(r.getValue().candidate)));
        status.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setGraphic(null);
                setText(null);
                PreviewRow row = getTableRow() == null ? null : getTableRow().getItem();
                if (!empty && row != null) {
                    Label badge = Widgets.badge(row.candidate.kind().label(), switch (row.candidate.kind()) {
                        case NEW -> "success";
                        case MATCHES_PLANNED, MATCHES_RECURRING -> "info";
                        case DUPLICATE, DUPLICATE_IN_FILE, POSSIBLE_DUPLICATE -> "warning";
                        case INVALID -> "danger-small";
                    });
                    Label detail = Widgets.label(text, "op-detail");
                    VBox box = new VBox(2, badge, detail);
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                }
            }
        });
        status.setPrefWidth(250);

        ObservableList<Choice<Long>> categoryChoices = FXCollections.observableArrayList();
        categoryChoices.add(NONE);
        ctx.services().categories().activeTree().forEach((root, children) -> {
            categoryChoices.add(new Choice<>(root.id(), root.name()));
            children.forEach(c -> categoryChoices.add(new Choice<>(c.id(), root.name() + " › " + c.name())));
        });
        TableColumn<PreviewRow, Choice<Long>> category = new TableColumn<>("Catégorie (modifiable)");
        category.setCellValueFactory(r -> r.getValue().category);
        category.setCellFactory(ComboBoxTableCell.forTableColumn(categoryChoices));
        category.setEditable(true);
        category.setPrefWidth(220);

        view.getColumns().addAll(List.of(include, date, label, amount, status, category));
        view.setFixedCellSize(46);

        long total = preview.size();
        long ambiguous = preview.stream().filter(p -> p.candidate.kind().needsDecision()).count();
        long invalid = preview.stream().filter(p -> p.candidate.kind() == ImportCandidate.Kind.INVALID).count();
        long matched = preview.stream().filter(p -> p.candidate.kind() == ImportCandidate.Kind.MATCHES_PLANNED
                || p.candidate.kind() == ImportCandidate.Kind.MATCHES_RECURRING).count();
        Label summary = Widgets.label(total + " ligne(s) · " + matched + " rapprochée(s) avec des opérations prévues · "
                + ambiguous + " doublon(s) ou cas ambigu(s) décoché(s) par défaut · " + invalid + " illisible(s)", "op-label");
        Label selected = Widgets.label("", "op-detail");
        Runnable updateSelected = () -> selected.setText(preview.stream().filter(p -> p.include.get()).count()
                + " ligne(s) seront importées. Cochez un doublon uniquement si vous êtes sûr qu'il s'agit d'une autre opération.");
        preview.forEach(p -> p.include.addListener((o, old, v) -> updateSelected.run()));
        updateSelected.run();

        CheckBox showAll = new CheckBox("N'afficher que les lignes à vérifier");
        showAll.setOnAction(e -> view.setItems(showAll.isSelected()
                ? preview.filtered(p -> p.candidate.kind() != ImportCandidate.Kind.NEW) : preview));
        VBox.setVgrow(view, Priority.ALWAYS);
        return new VBox(10, summary, Widgets.row(showAll), view, selected);
    }

    private String describe(ImportCandidate c) {
        return switch (c.kind()) {
            case NEW -> c.suggestion() == null ? "" : "catégorie proposée par " + c.suggestion().source().label()
                    + (c.suggestion().rule() != null ? " « " + c.suggestion().rule().pattern() + " »" : "");
            case DUPLICATE -> c.matchedExisting() == null ? "identifiant bancaire déjà importé"
                    : "comme « " + c.matchedExisting().label() + " » du " + Formats.shortDate(c.matchedExisting().date());
            case POSSIBLE_DUPLICATE -> "même montant que « " + c.matchedExisting().label() + " » du "
                    + Formats.shortDate(c.matchedExisting().date());
            case DUPLICATE_IN_FILE -> "ligne identique plus haut dans le fichier";
            case MATCHES_PLANNED, MATCHES_RECURRING -> "« " + c.matchedPlanned().label() + " » prévu le "
                    + Formats.shortDate(c.matchedPlanned().date());
            case INVALID -> "ligne " + c.row().line() + " : " + c.row().error();
        };
    }

    private ImportBatch commit() {
        List<ImportService.Decision> decisions = preview.stream()
                .map(p -> new ImportService.Decision(p.candidate, p.include.get(),
                        p.category.get() == null ? null : p.category.get().value()))
                .toList();
        if (decisions.stream().noneMatch(ImportService.Decision::include)) {
            throw new com.financeapp.core.service.BusinessException("Aucune ligne cochée : rien à importer.");
        }
        if (bankBatch != null) {
            return ctx.services().bankSync().commit(bankBatch, decisions);
        }
        return ctx.services().imports().commit(Widgets.selected(account), file.getName(), format, decisions);
    }
}
