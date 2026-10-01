package com.financeapp.desktop.ui.pages;

import com.financeapp.core.account.Account;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.Choice;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.TransactionDialog;
import com.financeapp.desktop.ui.dialogs.TransferDialog;
import javafx.animation.PauseTransition;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Liste filtrable des operations (tableau virtualise, chargement par pages). */
public final class TransactionsPage extends Page {

    private static final int PAGE_SIZE = 300;

    private enum Period {
        ALL("Toutes les dates"), THIS_MONTH("Ce mois-ci"), LAST_MONTH("Mois précédent"),
        LAST_3_MONTHS("3 derniers mois"), THIS_YEAR("Cette année"), FUTURE("À venir"), CUSTOM("Période personnalisée…");

        final String label;

        Period(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Plafond de securite de l'export (bien au-dela d'un historique personnel). */
    private static final int EXPORT_LIMIT = 1_000_000;

    private final TableView<Transaction> table = new TableView<>();
    private final ComboBox<Choice<Long>> accountFilter = new ComboBox<>();
    private final ComboBox<Choice<Set<TransactionStatus>>> statusFilter = new ComboBox<>();
    private final ComboBox<Period> periodFilter = new ComboBox<>();
    private final TextField search = new TextField();
    private final ComboBox<Choice<Long>> categoryFilter = new ComboBox<>();
    private final ComboBox<Choice<Long>> tagFilter = new ComboBox<>();
    private final TextField minAmount = new TextField();
    private final TextField maxAmount = new TextField();
    private final javafx.scene.control.DatePicker fromDate = Widgets.datePicker(null);
    private final javafx.scene.control.DatePicker toDate = Widgets.datePicker(null);
    private final Label footer = Widgets.label("", "muted");
    private final Button loadMore = new Button("Charger plus");
    private Map<Long, Account> accounts = Map.of();
    private Map<Long, String> categoryNames = Map.of();
    private Map<Long, String> tagNames = Map.of();
    private int limit = PAGE_SIZE;
    private TransactionQuery lastQuery;

    public TransactionsPage(UiContext ctx) {
        super(ctx);
        buildTable();

        Button expense = action("−  Dépense", "primary", () -> openNew(TransactionType.EXPENSE));
        Button income = action("+  Revenu", "secondary", () -> openNew(TransactionType.INCOME));
        Button transfer = action("⇄  Virement", "secondary", () -> dialogThenRefresh(new TransferDialog(ctx, null)));
        search.setPromptText("Rechercher un libellé…");
        search.setPrefWidth(240);
        PauseTransition debounce = new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> resetAndRefresh());
        search.textProperty().addListener((o, old, v) -> debounce.playFromStart());

        statusFilter.getItems().setAll(
                new Choice<>(null, "Tous les statuts"),
                new Choice<>(EnumSet.of(TransactionStatus.COMPLETED, TransactionStatus.PENDING), "Effectuées et en attente"),
                new Choice<>(EnumSet.of(TransactionStatus.PLANNED), "Prévues"),
                new Choice<>(EnumSet.of(TransactionStatus.PENDING), "En attente"),
                new Choice<>(EnumSet.of(TransactionStatus.CANCELLED), "Annulées"));
        statusFilter.getSelectionModel().selectFirst();
        periodFilter.getItems().setAll(Period.values());
        periodFilter.setValue(Period.ALL);
        accountFilter.setOnAction(e -> resetAndRefresh());
        statusFilter.setOnAction(e -> resetAndRefresh());
        periodFilter.setOnAction(e -> resetAndRefresh());
        categoryFilter.setOnAction(e -> resetAndRefresh());
        categoryFilter.setPrefWidth(210);
        tagFilter.setPrefWidth(170);
        minAmount.setPromptText("Montant min.");
        maxAmount.setPromptText("max.");
        minAmount.setPrefWidth(105);
        maxAmount.setPrefWidth(80);
        minAmount.textProperty().addListener((o, old, v) -> debounce.playFromStart());
        maxAmount.textProperty().addListener((o, old, v) -> debounce.playFromStart());
        for (var picker : new javafx.scene.control.DatePicker[]{fromDate, toDate}) {
            picker.setPrefWidth(135);
            picker.visibleProperty().bind(periodFilter.valueProperty().isEqualTo(Period.CUSTOM));
            picker.managedProperty().bind(picker.visibleProperty());
            picker.valueProperty().addListener((o, old, v) -> resetAndRefresh());
        }
        fromDate.setPromptText("Du");
        toDate.setPromptText("Au");
        loadMore.getStyleClass().add("ghost");
        loadMore.setOnAction(e -> {
            limit += PAGE_SIZE;
            refresh();
        });

        Button export = action("⇪  Exporter (CSV)…", "ghost", this::exportCsv);
        export.setTooltip(new javafx.scene.control.Tooltip("Exporte toutes les opérations de la recherche en cours "
                + "(filtres compris) dans un fichier CSV pour Excel ou LibreOffice"));
        HBox actions = Widgets.row(expense, income, transfer, Widgets.spacer(), export, search);
        javafx.scene.layout.FlowPane filters = new javafx.scene.layout.FlowPane(8, 8, Widgets.label("Filtres", "muted"),
                accountFilter, categoryFilter, tagFilter, periodFilter, fromDate, toDate, statusFilter, minAmount, maxAmount);
        filters.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox bottom = Widgets.row(footer, Widgets.spacer(), loadMore);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getChildren().setAll(actions, filters, table, bottom);
    }

    @Override
    public Node view() {
        return content;
    }

    @Override
    public String title() {
        return "Transactions";
    }

    private void resetAndRefresh() {
        limit = PAGE_SIZE;
        refresh();
    }

    @Override
    public void refresh() {
        accounts = ctx.services().accounts().findAll().stream().collect(Collectors.toMap(Account::id, a -> a));
        categoryNames = ctx.services().categories().fullNames();
        tagNames = ctx.services().tags().names();
        Long selectedTag = Widgets.selected(tagFilter);
        tagFilter.setOnAction(null);
        tagFilter.getItems().setAll(new Choice<>(null, "Toutes les étiquettes"));
        ctx.services().tags().findAll().forEach(t -> tagFilter.getItems().add(new Choice<>(t.id(), "[" + t.name() + "]")));
        Widgets.select(tagFilter, selectedTag);
        if (tagFilter.getValue() == null) {
            tagFilter.getSelectionModel().selectFirst();
        }
        tagFilter.setOnAction(e -> resetAndRefresh());
        tagFilter.setVisible(tagFilter.getItems().size() > 1); // rien a filtrer tant qu'aucune etiquette n'existe
        tagFilter.setManaged(tagFilter.isVisible());
        Long selectedAccount = Widgets.selected(accountFilter);
        accountFilter.setOnAction(null);
        accountFilter.getItems().setAll(new Choice<>(null, "Tous les comptes"));
        accounts.values().stream().sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .forEach(a -> accountFilter.getItems().add(new Choice<>(a.id(), a.name())));
        Widgets.select(accountFilter, selectedAccount);
        if (accountFilter.getValue() == null) {
            accountFilter.getSelectionModel().selectFirst();
        }
        accountFilter.setOnAction(e -> resetAndRefresh());

        Long selectedCategory = Widgets.selected(categoryFilter);
        categoryFilter.setOnAction(null);
        categoryFilter.getItems().setAll(new Choice<>(null, "Toutes les catégories"));
        ctx.services().categories().activeTree().forEach((root, children) -> {
            categoryFilter.getItems().add(new Choice<>(root.id(), root.name()));
            children.forEach(c -> categoryFilter.getItems().add(new Choice<>(c.id(), "   " + root.name() + " › " + c.name())));
        });
        Widgets.select(categoryFilter, selectedCategory);
        if (categoryFilter.getValue() == null) {
            categoryFilter.getSelectionModel().selectFirst();
        }
        categoryFilter.setOnAction(e -> resetAndRefresh());

        LocalDate today = ctx.services().planning().today();
        LocalDate from = null;
        LocalDate to = null;
        switch (periodFilter.getValue()) {
            case THIS_MONTH -> {
                from = today.withDayOfMonth(1);
                to = today.with(TemporalAdjusters.lastDayOfMonth());
            }
            case LAST_MONTH -> {
                from = today.minusMonths(1).withDayOfMonth(1);
                to = from.with(TemporalAdjusters.lastDayOfMonth());
            }
            case LAST_3_MONTHS -> from = today.minusMonths(3);
            case THIS_YEAR -> from = today.withDayOfYear(1);
            case FUTURE -> from = today.plusDays(1);
            case CUSTOM -> {
                from = fromDate.getValue();
                to = toDate.getValue();
            }
            case ALL -> { }
        }
        TransactionQuery query = new TransactionQuery(Widgets.selected(accountFilter), from, to,
                search.getText().isBlank() ? null : search.getText(), Widgets.selected(categoryFilter),
                Widgets.selected(statusFilter), null, amount(minAmount), amount(maxAmount), limit, 0,
                Widgets.selected(tagFilter));
        lastQuery = query;
        List<Transaction> rows = ctx.services().transactions().search(query);
        table.getItems().setAll(rows);
        loadMore.setVisible(rows.size() >= limit);
        updateFooter(rows, query);
        table.refresh();
    }

    /** "Ventilée : Courses + Maison" pour une operation ventilee, sinon sa categorie. */
    private String categoryText(Transaction t) {
        if (!t.isSplit()) {
            return categoryNames.getOrDefault(t.categoryId(), "");
        }
        return "Ventilée : " + t.splits().stream()
                .map(l -> l.categoryId() == null ? "Sans catégorie" : shortName(categoryNames.getOrDefault(l.categoryId(), "?")))
                .collect(Collectors.joining(" + "));
    }

    private static String shortName(String fullName) {
        int arrow = fullName.lastIndexOf(" › ");
        return arrow < 0 ? fullName : fullName.substring(arrow + 3);
    }

    /** Etiquettes affichees apres le libelle : "  [travaux] [vacances]". */
    private String tagsText(Transaction t) {
        if (t.tagIds().isEmpty()) {
            return "";
        }
        return "   " + t.tagIds().stream().map(id -> tagNames.getOrDefault(id, "")).filter(n -> !n.isEmpty())
                .sorted(String.CASE_INSENSITIVE_ORDER).map(n -> "[" + n + "]").collect(Collectors.joining(" "));
    }

    /** Export CSV de TOUS les resultats de la recherche affichee (pas seulement les lignes chargees). */
    private void exportCsv() {
        if (lastQuery == null) {
            return;
        }
        List<Transaction> rows = ctx.services().transactions().search(lastQuery.withLimit(EXPORT_LIMIT));
        if (rows.isEmpty()) {
            Dialogs.info(window(), "Exporter", "Aucune opération ne correspond à la recherche : rien à exporter.");
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Exporter " + rows.size() + " opération(s)");
        chooser.setInitialFileName("operations-" + ctx.services().planning().today() + ".csv");
        // Documents (sinon dossier personnel) : jamais le dossier d'installation de l'application.
        java.io.File home = new java.io.File(System.getProperty("user.home"));
        java.io.File documents = new java.io.File(home, "Documents");
        chooser.setInitialDirectory(documents.isDirectory() ? documents : home);
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("Fichier CSV (*.csv)", "*.csv"));
        java.io.File file = chooser.showSaveDialog(window());
        if (file == null) {
            return;
        }
        java.nio.file.Path path = file.toPath();
        if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            path = path.resolveSibling(path.getFileName() + ".csv");
        }
        Map<Long, String> accountNames = accounts.values().stream().collect(Collectors.toMap(Account::id, Account::name));
        try (var out = java.nio.file.Files.newBufferedWriter(path, java.nio.charset.StandardCharsets.UTF_8)) {
            int n = com.financeapp.core.export.TransactionCsvExporter.write(rows, accountNames, categoryNames, tagNames, out);
            Dialogs.info(window(), "Export terminé", n + " opération(s) exportée(s) dans :\n" + path
                    + "\n\nAttention : ce fichier n'est pas chiffré. Rangez-le en lieu sûr ou supprimez-le après usage.");
        } catch (java.io.IOException | RuntimeException ex) {
            Dialogs.error(window(), new IllegalStateException("Export impossible : " + ex.getMessage(), ex));
        }
    }

    /** Montant de filtre saisi (valeur absolue) ; saisie invalide signalee et ignoree. */
    private static java.math.BigDecimal amount(TextField field) {
        field.getStyleClass().remove("field-invalid");
        if (field.getText().isBlank()) {
            return null;
        }
        var value = com.financeapp.desktop.ui.common.AmountParser.parse(field.getText()).map(java.math.BigDecimal::abs);
        if (value.isEmpty()) {
            field.getStyleClass().add("field-invalid");
        }
        return value.orElse(null);
    }

    /** Totaux calcules sur TOUS les resultats de la recherche, pas seulement les lignes chargees. */
    private void updateFooter(List<Transaction> rows, TransactionQuery query) {
        Formats f = ctx.formats();
        var totals = ctx.services().transactions().summarize(query, ctx.services().settings().baseCurrency());
        String shown = rows.size() >= limit ? " (" + rows.size() + " affichées)" : "";
        footer.setText(totals.count() + " opération(s)" + shown
                + " · total dépensé " + f.money(totals.expenses().negate())
                + (totals.expenseCount() > 0 ? " · moyenne " + f.money(totals.averageExpense()) : "")
                + " · revenus " + f.money(totals.income())
                + "   (virements internes et annulations exclus)");
    }

    private void buildTable() {
        table.setPlaceholder(Widgets.emptyState("Aucune opération ne correspond. Utilisez « − Dépense » ou « + Revenu » pour en ajouter."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Transaction, LocalDate> date = new TableColumn<>("Date");
        date.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().date()));
        date.setCellFactory(col -> textCell(Formats::date));
        date.setPrefWidth(100);
        date.setMaxWidth(120);

        TableColumn<Transaction, String> label = new TableColumn<>("Libellé");
        label.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label() + tagsText(c.getValue())));
        label.setPrefWidth(260);

        TableColumn<Transaction, String> category = new TableColumn<>("Catégorie");
        category.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isTransfer()
                ? transferText(c.getValue()) : categoryText(c.getValue())));
        category.setPrefWidth(220);

        TableColumn<Transaction, String> account = new TableColumn<>("Compte");
        account.setCellValueFactory(c -> new SimpleStringProperty(
                accounts.containsKey(c.getValue().accountId()) ? accounts.get(c.getValue().accountId()).name() : ""));
        account.setPrefWidth(150);

        TableColumn<Transaction, TransactionStatus> status = new TableColumn<>("Statut");
        status.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().status()));
        status.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(TransactionStatus s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                setGraphic(empty || s == null ? null : Widgets.badge(s.label(), switch (s) {
                    case COMPLETED -> "success";
                    case PENDING -> "warning";
                    case PLANNED -> "info";
                    case CANCELLED -> "neutral";
                }));
            }
        });
        status.setPrefWidth(110);
        status.setMaxWidth(130);

        TableColumn<Transaction, Money> amount = new TableColumn<>("Montant");
        amount.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().amount()));
        amount.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Money m, boolean empty) {
                super.updateItem(m, empty);
                getStyleClass().removeAll("amount-negative", "amount-positive", "amount-zero");
                if (empty || m == null) {
                    setText(null);
                } else {
                    setText(ctx.formats().signed(m));
                    getStyleClass().add(Formats.signClass(m));
                }
            }
        });
        amount.getStyleClass().add("amount-column");
        amount.setPrefWidth(130);
        amount.setMaxWidth(160);

        List.of(date, label, category, account, status, amount).forEach(c -> table.getColumns().add(c));

        table.setRowFactory(tv -> {
            TableRow<Transaction> row = new TableRow<>() {
                @Override
                protected void updateItem(Transaction t, boolean empty) {
                    super.updateItem(t, empty);
                    getStyleClass().remove("row-cancelled");
                    setTooltip(null);
                    if (!empty && t != null) {
                        if (t.status() == TransactionStatus.CANCELLED) {
                            getStyleClass().add("row-cancelled");
                        }
                        if (t.note() != null) {
                            setTooltip(new Tooltip(t.note()));
                        }
                    }
                }
            };
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    edit(row.getItem());
                }
            });
            MenuItem editItem = new MenuItem("Modifier…");
            editItem.setOnAction(e -> edit(row.getItem()));
            MenuItem done = new MenuItem("Marquer comme effectuée");
            done.setOnAction(e -> setStatus(row.getItem(), TransactionStatus.COMPLETED));
            MenuItem cancel = new MenuItem("Marquer comme annulée");
            cancel.setOnAction(e -> setStatus(row.getItem(), TransactionStatus.CANCELLED));
            MenuItem delete = new MenuItem("Supprimer…");
            delete.setOnAction(e -> delete(row.getItem()));
            ContextMenu menu = new ContextMenu(editItem, done, cancel, delete);
            row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null).otherwise(menu));
            return row;
        });
    }

    private String transferText(Transaction t) {
        Account other = t.transferAccountId() == null ? null : accounts.get(t.transferAccountId());
        String name = other == null ? "?" : other.name();
        return t.amount().isNegative() ? "Virement vers " + name : "Virement depuis " + name;
    }

    private <T> TableCell<Transaction, T> textCell(Function<T, String> formatter) {
        return new TableCell<>() {
            @Override
            protected void updateItem(T value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : formatter.apply(value));
            }
        };
    }

    private Button action(String text, String style, Runnable r) {
        Button b = new Button(text);
        b.getStyleClass().add(style);
        b.setOnAction(e -> r.run());
        return b;
    }

    private void openNew(TransactionType type) {
        dialogThenRefresh(new TransactionDialog(ctx, null, type, Widgets.selected(accountFilter)));
    }

    private void dialogThenRefresh(javafx.scene.control.Dialog<?> dialog) {
        dialog.showAndWait().ifPresent(r -> ctx.events().fireChanged());
    }

    private void edit(Transaction t) {
        if (t == null) {
            return;
        }
        try {
            if (t.isTransfer()) {
                dialogThenRefresh(new TransferDialog(ctx, t));
                return;
            }
            new TransactionDialog(ctx, t, t.type(), null).showAndWait().ifPresent(saved -> {
                // Correction de categorie : proposer une regle pour les prochaines fois.
                if (saved.categoryId() != null && !java.util.Objects.equals(saved.categoryId(), t.categoryId())) {
                    com.financeapp.desktop.ui.dialogs.RuleProposal.offer(ctx, saved.label(), saved.type(), saved.categoryId());
                }
                ctx.events().fireChanged();
            });
        } catch (RuntimeException e) {
            Dialogs.error(window(), e);
        }
    }

    private void setStatus(Transaction t, TransactionStatus status) {
        try {
            ctx.services().transactions().setStatus(t.id(), status);
            ctx.events().fireChanged();
        } catch (RuntimeException e) {
            Dialogs.error(window(), e);
        }
    }

    private void delete(Transaction t) {
        String what = t.isTransfer() ? "ce virement (les deux lignes)" : "« " + t.label() + " »";
        if (Dialogs.confirm(window(), "Supprimer", "Supprimer définitivement " + what + " ?\n"
                + "Pour garder une trace, préférez « Marquer comme annulée ».", "Supprimer")) {
            try {
                ctx.services().transactions().delete(t.id());
                ctx.events().fireChanged();
            } catch (RuntimeException e) {
                Dialogs.error(window(), e);
            }
        }
    }
}
