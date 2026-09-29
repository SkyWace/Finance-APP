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
        LAST_3_MONTHS("3 derniers mois"), THIS_YEAR("Cette année"), FUTURE("À venir");

        final String label;

        Period(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final TableView<Transaction> table = new TableView<>();
    private final ComboBox<Choice<Long>> accountFilter = new ComboBox<>();
    private final ComboBox<Choice<Set<TransactionStatus>>> statusFilter = new ComboBox<>();
    private final ComboBox<Period> periodFilter = new ComboBox<>();
    private final TextField search = new TextField();
    private final Label footer = Widgets.label("", "muted");
    private final Button loadMore = new Button("Charger plus");
    private Map<Long, Account> accounts = Map.of();
    private Map<Long, String> categoryNames = Map.of();
    private int limit = PAGE_SIZE;

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
        loadMore.getStyleClass().add("ghost");
        loadMore.setOnAction(e -> {
            limit += PAGE_SIZE;
            refresh();
        });

        HBox actions = Widgets.row(expense, income, transfer, Widgets.spacer(), search);
        HBox filters = Widgets.row(Widgets.label("Filtres", "muted"), accountFilter, periodFilter, statusFilter);
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
            case ALL -> { }
        }
        TransactionQuery query = new TransactionQuery(Widgets.selected(accountFilter), from, to,
                search.getText().isBlank() ? null : search.getText(), null,
                Widgets.selected(statusFilter), limit, 0);
        List<Transaction> rows = ctx.services().transactions().search(query);
        table.getItems().setAll(rows);
        loadMore.setVisible(rows.size() >= limit);
        updateFooter(rows);
        table.refresh();
    }

    private void updateFooter(List<Transaction> rows) {
        Formats f = ctx.formats();
        List<Transaction> counted = rows.stream()
                .filter(t -> !t.isTransfer() && t.status() != TransactionStatus.CANCELLED).toList();
        if (counted.isEmpty() || counted.stream().map(t -> t.amount().currency()).distinct().count() > 1) {
            footer.setText(rows.size() + " opération(s)");
            return;
        }
        Money zero = Money.zero(counted.getFirst().amount().currency());
        Money in = counted.stream().map(Transaction::amount).filter(Money::isPositive).reduce(zero, Money::plus);
        Money out = counted.stream().map(Transaction::amount).filter(Money::isNegative).reduce(zero, Money::plus);
        footer.setText(rows.size() + " opération(s) · entrées " + f.signed(in) + " · sorties " + f.signed(out)
                + " (virements internes et annulations exclus)");
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
        label.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().label()));
        label.setPrefWidth(260);

        TableColumn<Transaction, String> category = new TableColumn<>("Catégorie");
        category.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isTransfer()
                ? transferText(c.getValue()) : categoryNames.getOrDefault(c.getValue().categoryId(), "")));
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
            dialogThenRefresh(t.isTransfer() ? new TransferDialog(ctx, t) : new TransactionDialog(ctx, t, t.type(), null));
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
