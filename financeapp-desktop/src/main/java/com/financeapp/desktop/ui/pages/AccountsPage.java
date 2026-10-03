package com.financeapp.desktop.ui.pages;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.desktop.ui.common.Dialogs;
import com.financeapp.desktop.ui.common.Formats;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import com.financeapp.desktop.ui.dialogs.AccountDialog;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Liste des comptes, soldes et total. */
public final class AccountsPage extends Page {

    private final CheckBox showArchived = new CheckBox("Afficher les comptes archivés");

    public AccountsPage(UiContext ctx) {
        super(ctx);
        showArchived.setOnAction(e -> refresh());
    }

    @Override
    public String title() {
        return "Comptes";
    }

    @Override
    public void refresh() {
        Formats f = ctx.formats();
        List<Account> accounts = ctx.services().accounts().findAll();
        Map<Long, Money> balances = ctx.services().accounts().balances();

        Button add = new Button("+  Nouveau compte");
        add.getStyleClass().add("primary");
        add.setOnAction(e -> edit(null));
        Button addSavings = new Button("+  Ajouter une épargne");
        addSavings.getStyleClass().add("secondary");
        addSavings.setTooltip(new javafx.scene.control.Tooltip("Livret A, LDDS, PEL, assurance-vie, PEA, PER…"));
        addSavings.setOnAction(e -> new com.financeapp.desktop.ui.dialogs.SavingsAccountDialog(ctx).showAndWait()
                .ifPresent(a -> ctx.events().fireChanged()));
        HBox toolbar = Widgets.row(add, addSavings, Widgets.spacer(), showArchived);

        // Comptes courants, epargne, puis especes et autres : chacun avec son sous-total.
        Map<AccountType.Group, List<Account>> groups = new java.util.EnumMap<>(AccountType.Group.class);
        for (Account a : accounts) {
            if (a.archived() && !showArchived.isSelected()) {
                continue;
            }
            AccountType.Group g = a.type().group() == AccountType.Group.OTHER ? AccountType.Group.CASH : a.type().group();
            groups.computeIfAbsent(g, k -> new java.util.ArrayList<>()).add(a);
        }
        content.getChildren().setAll(toolbar);
        Map<Currency, Money> totals = new LinkedHashMap<>();
        for (AccountType.Group g : List.of(AccountType.Group.CURRENT, AccountType.Group.SAVINGS, AccountType.Group.CASH)) {
            List<Account> members = groups.getOrDefault(g, List.of());
            if (members.isEmpty()) {
                continue;
            }
            VBox list = new VBox(8);
            Map<Currency, Money> subtotals = new LinkedHashMap<>();
            for (Account a : members) {
                Money balance = balances.get(a.id());
                if (!a.archived()) {
                    subtotals.merge(a.currency(), balance, Money::plus);
                    totals.merge(a.currency(), balance, Money::plus);
                }
                list.getChildren().add(accountRow(a, balance, f));
            }
            subtotals.forEach((cur, subtotal) -> list.getChildren().add(Widgets.row(
                    Widgets.label("Sous-total " + groupTitle(g).toLowerCase(java.util.Locale.ROOT)
                            + (subtotals.size() > 1 ? " (" + cur.getCurrencyCode() + ")" : ""), "muted"),
                    Widgets.spacer(), Widgets.label(f.money(subtotal), "op-label"))));
            content.getChildren().add(Widgets.section(groupTitle(g) + " · " + members.size(), list));
        }
        if (groups.isEmpty()) {
            content.getChildren().add(Widgets.emptyState("Aucun compte. Créez votre premier compte pour commencer."));
        }
        VBox totalBox = new VBox(4);
        totals.forEach((cur, total) -> {
            Label amount = Widgets.label(f.money(total), "total-value");
            totalBox.getChildren().add(Widgets.row(Widgets.label("TOTAL " + (totals.size() > 1 ? cur.getCurrencyCode() : ""),
                    "total-label"), Widgets.spacer(), amount));
        });
        totalBox.getStyleClass().add("card");

        Label hint = Widgets.label("Les comptes marqués « inclus dans le disponible » (par défaut : courants, joints, espèces, "
                + "cartes prépayées) servent au calcul du disponible réel et des prévisions. Détail de l'épargne "
                + "(plafonds, valeurs saisies, évolution du patrimoine) : écran Épargne.", "muted");
        hint.setWrapText(true);
        content.getChildren().addAll(totalBox, hint);
    }

    private static String groupTitle(AccountType.Group g) {
        return switch (g) {
            case CURRENT -> "Comptes courants";
            case SAVINGS -> "Épargne";
            case CASH, OTHER -> "Espèces et autres";
        };
    }

    private HBox accountRow(Account a, Money balance, Formats f) {
        Region colorBar = new Region();
        colorBar.getStyleClass().add("account-color");
        colorBar.setStyle("-fx-background-color: " + (a.color() != null ? a.color() : "#6E9BFF") + ";");
        Label name = Widgets.label(a.name(), "account-name");
        HBox badges = new HBox(6, Widgets.label(a.type().label(), "muted"));
        if (a.includeInAvailable()) {
            badges.getChildren().add(Widgets.badge("inclus dans le disponible", "info"));
        }
        if (a.archived()) {
            badges.getChildren().add(Widgets.badge("archivé", "neutral"));
        }
        VBox texts = new VBox(4, name, badges);
        Label amount = Widgets.label(f.money(balance), "account-balance", Formats.signClass(balance));

        Button edit = small("Modifier", () -> edit(a));
        Button archive = small(a.archived() ? "Réactiver" : "Archiver", () -> {
            ctx.services().accounts().setArchived(a.id(), !a.archived());
            ctx.events().fireChanged();
        });
        Button delete = small("Supprimer", () -> {
            if (Dialogs.confirm(window(), "Supprimer le compte",
                    "Supprimer définitivement « " + a.name() + " » ? Seul un compte sans aucune opération peut être supprimé.",
                    "Supprimer")) {
                ctx.services().accounts().delete(a.id());
                ctx.events().fireChanged();
            }
        });
        HBox row = new HBox(14, colorBar, texts, Widgets.spacer(), amount, edit, archive, delete);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("card", "account-row");
        if (a.archived()) {
            row.getStyleClass().add("archived");
        }
        return row;
    }

    private Button small(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("ghost");
        b.setOnAction(e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                Dialogs.error(window(), ex);
            }
        });
        return b;
    }

    private void edit(Account a) {
        new AccountDialog(ctx, a).showAndWait().ifPresent(saved -> ctx.events().fireChanged());
    }
}
