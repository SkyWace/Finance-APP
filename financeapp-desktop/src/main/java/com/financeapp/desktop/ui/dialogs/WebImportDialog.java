package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.account.Account;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.WebImportService;
import com.financeapp.core.service.WebImportService.Item;
import com.financeapp.core.service.WebImportService.Kind;
import com.financeapp.core.service.WebImportService.Target;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reprise des operations saisies sur la version web : association des comptes du site,
 * puis apercu (nouvelles, deja presentes, a verifier) avant l'import.
 */
public final class WebImportDialog extends Dialog<WebImportService.Result> {

    private static final class Row {
        final Item item;
        final SimpleBooleanProperty include;

        Row(Item item) {
            this.item = item;
            this.include = new SimpleBooleanProperty(item.kind().includedByDefault());
        }
    }

    private final UiContext ctx;
    private final String dataJson;
    private final String fileName;
    private final Map<Long, ComboBox<Choice<Target>>> targets = new HashMap<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>();
    private final CheckBox onlyRelevant = new CheckBox("Masquer les opérations déjà présentes");
    private final Label summary = Widgets.label("", "op-label");
    private final Label selected = Widgets.label("", "op-detail");
    private final Label error = Widgets.label("", "form-error");
    private final ButtonType importButton = new ButtonType("Importer", ButtonBar.ButtonData.OK_DONE);
    private WebImportService.Result result;

    public WebImportDialog(UiContext ctx, String profileName, String dataJson, String fileName) {
        this.ctx = ctx;
        this.dataJson = dataJson;
        this.fileName = fileName;
        setTitle("Importer depuis la version web");
        setHeaderText(null);
        Dialogs.style(getDialogPane(), ctx.window(), this);
        getDialogPane().getStyleClass().add("form-dialog");
        getDialogPane().setPrefSize(1000, 700);
        setResizable(true);
        getDialogPane().getButtonTypes().addAll(importButton, new ButtonType("Annuler", ButtonBar.ButtonData.CANCEL_CLOSE));

        WebImportService service = ctx.services().webImport();
        List<WebImportService.WebAccount> webAccounts = service.accounts(dataJson);
        Label intro = Widgets.label("Profil du site « " + profileName + " ». Seules les opérations absentes de "
                + "l'application sont proposées ; les doublons possibles et les opérations modifiées sur le site sont "
                + "signalés et décochés : rien d'ambigu n'est importé sans votre accord. L'import se défait depuis "
                + "l'écran Imports.", "hint");
        intro.setWrapText(true);
        intro.setMinHeight(Region.USE_PREF_SIZE);

        GridPane mapping = new GridPane();
        mapping.setHgap(12);
        mapping.setVgap(8);
        Map<Long, Target> defaults = WebImportService.defaultTargets(webAccounts);
        List<Account> mine = ctx.services().accounts().findAll().stream()
                .filter(a -> a.currency().equals(ctx.services().settings().baseCurrency())).toList();
        int line = 0;
        for (WebImportService.WebAccount a : webAccounts) {
            ComboBox<Choice<Target>> combo = new ComboBox<>();
            Choice<Target> skip = new Choice<>(new Target.Skip(), "Ne pas importer");
            Choice<Target> create = new Choice<>(new Target.Create(), "Créer le compte « " + a.name() + " »");
            combo.getItems().add(skip);
            mine.forEach(m -> combo.getItems().add(new Choice<>(new Target.Existing(m.id()),
                    m.name() + (m.archived() ? " (archivé)" : ""))));
            combo.getItems().add(create);
            Target initial = defaults.get(a.webId());
            combo.getSelectionModel().select(combo.getItems().stream().filter(c -> c.value().equals(initial))
                    .findFirst().orElse(skip));
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.valueProperty().addListener((o, old, v) -> refresh());
            targets.put(a.webId(), combo);
            mapping.addRow(line++, Widgets.label(a.name() + " (" + a.operations() + " opération(s))", "form-label"), combo);
        }

        buildTable();
        onlyRelevant.setSelected(true);
        onlyRelevant.setOnAction(e -> applyFilter());
        error.setWrapText(true);
        for (Label l : List.of(summary, selected, error)) {
            l.setWrapText(true);
            l.setMinHeight(Region.USE_PREF_SIZE);
        }
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        VBox.setVgrow(table, Priority.ALWAYS);
        getDialogPane().setContent(new VBox(12, intro, Widgets.label("Comptes du site", "section-title"), mapping,
                summary, Widgets.row(onlyRelevant), table, selected, error));
        refresh();

        getDialogPane().lookupButton(importButton).addEventFilter(ActionEvent.ACTION, e -> {
            try {
                Set<Integer> included = rows.stream().filter(r -> r.include.get()).map(r -> r.item.index())
                        .collect(Collectors.toSet());
                if (included.isEmpty()) {
                    e.consume();
                    error.setText("Aucune opération cochée.");
                    return;
                }
                result = service.commit(dataJson, currentTargets(), included, fileName);
            } catch (com.financeapp.core.service.BusinessException ex) {
                e.consume();
                error.setText(ex.getMessage());
            } catch (RuntimeException ex) {
                e.consume();
                Dialogs.error(getDialogPane().getScene().getWindow(), ex);
            }
        });
        setResultConverter(b -> b == importButton ? result : null);
    }

    private Map<Long, Target> currentTargets() {
        Map<Long, Target> map = new HashMap<>();
        targets.forEach((id, combo) -> map.put(id, combo.getValue() == null ? new Target.Skip() : combo.getValue().value()));
        return map;
    }

    private void refresh() {
        error.setText("");
        WebImportService.Plan plan = ctx.services().webImport().plan(dataJson, currentTargets());
        rows.setAll(plan.items().stream().map(Row::new).toList());
        rows.forEach(r -> r.include.addListener((o, old, v) -> updateSelected()));
        summary.setText(plan.count(Kind.NEW) + " nouvelle(s) · " + plan.count(Kind.REALIZES_PLANNED)
                + " opération(s) prévue(s) réalisée(s) · " + (plan.count(Kind.POSSIBLE_DUPLICATE)
                + plan.count(Kind.TRANSFER_TO_SKIPPED)) + " à vérifier · " + plan.count(Kind.MODIFIED)
                + " modifiée(s) sur le site · " + plan.count(Kind.ALREADY_PRESENT) + " déjà présente(s)"
                + (plan.skippedAccountOperations() > 0 ? " · " + plan.skippedAccountOperations()
                + " d'un compte non importé" : ""));
        applyFilter();
        updateSelected();
    }

    private void applyFilter() {
        table.setItems(onlyRelevant.isSelected() ? rows.filtered(r -> r.item.kind() != Kind.ALREADY_PRESENT) : rows);
    }

    private void updateSelected() {
        long n = rows.stream().filter(r -> r.include.get()).count();
        selected.setText(n + " opération(s) seront importées. Cochez une ligne « à vérifier » uniquement si vous êtes "
                + "sûr qu'il s'agit d'une autre opération. Une opération modifiée sur le site n'est pas reprise : "
                + "reportez la modification à la main si besoin.");
        getDialogPane().lookupButton(importButton).setDisable(n == 0);
    }

    private void buildTable() {
        Formats f = ctx.formats();
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Widgets.label("Aucune opération à reprendre pour ces comptes.", "op-detail"));

        TableColumn<Row, Boolean> include = new TableColumn<>("Importer");
        include.setCellValueFactory(r -> r.getValue().include);
        include.setCellFactory(col -> new CheckBoxTableCell<>() {
            @Override
            public void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                Row row = getTableRow() == null ? null : getTableRow().getItem();
                setDisable(row != null && (row.item.kind() == Kind.ALREADY_PRESENT || row.item.kind() == Kind.MODIFIED));
            }
        });
        include.setEditable(true);
        include.setPrefWidth(70);
        include.setMaxWidth(80);

        TableColumn<Row, String> date = new TableColumn<>("Date");
        date.setCellValueFactory(r -> new SimpleStringProperty(Formats.date(r.getValue().item.date())));
        date.setMaxWidth(110);

        TableColumn<Row, String> label = new TableColumn<>("Libellé");
        label.setCellValueFactory(r -> {
            Item i = r.getValue().item;
            String detail = i.category() != null ? i.category() : i.webToAccountId() != null ? "Virement" : "";
            return new SimpleStringProperty(i.label() + (detail.isEmpty() ? "" : " · " + detail)
                    + (i.status() == com.financeapp.core.transaction.TransactionStatus.COMPLETED ? ""
                    : " · " + i.status().label().toLowerCase(java.util.Locale.ROOT)));
        });
        label.setPrefWidth(300);

        TableColumn<Row, String> amount = new TableColumn<>("Montant");
        amount.setCellValueFactory(r -> new SimpleStringProperty(
                f.signed(Money.ofMinor(r.getValue().item.amount(), ctx.services().settings().baseCurrency()))));
        amount.setMaxWidth(130);
        amount.getStyleClass().add("amount-column");

        TableColumn<Row, String> state = new TableColumn<>("Statut");
        state.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().item.kind().label()));
        state.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setGraphic(null);
                setText(null);
                Row row = getTableRow() == null ? null : getTableRow().getItem();
                if (!empty && row != null) {
                    Kind kind = row.item.kind();
                    Label badge = Widgets.badge(symbol(kind) + "  " + kind.label(), switch (kind) {
                        case NEW -> "success";
                        case REALIZES_PLANNED -> "info";
                        case POSSIBLE_DUPLICATE, TRANSFER_TO_SKIPPED, MODIFIED -> "warning";
                        case ALREADY_PRESENT -> "neutral";
                    });
                    VBox box = new VBox(2, badge);
                    if (row.item.matched() != null && kind != Kind.ALREADY_PRESENT) {
                        box.getChildren().add(Widgets.label("Dans l'application : " + row.item.matched().label() + " du "
                                + Formats.date(row.item.matched().date()), "op-detail"));
                    }
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                }
            }
        });
        state.setPrefWidth(280);

        table.getColumns().addAll(List.of(include, date, label, amount, state));
        table.setFixedCellSize(46);
    }

    /** Symbole en plus de la couleur (jamais la couleur seule). */
    private static String symbol(Kind kind) {
        return switch (kind) {
            case NEW -> "+";
            case REALIZES_PLANNED -> "✓";
            case POSSIBLE_DUPLICATE, TRANSFER_TO_SKIPPED, MODIFIED -> "!";
            case ALREADY_PRESENT -> "=";
        };
    }
}
