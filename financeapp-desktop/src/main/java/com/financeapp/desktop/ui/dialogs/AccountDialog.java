package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.BusinessException;
import com.financeapp.desktop.ui.common.AmountParser;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.paint.Color;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

/** Creation ou modification d'un compte (dont le solde initial). */
public final class AccountDialog extends FormDialog<Account> {

    private static final List<String> CURRENCIES = List.of("EUR", "CHF", "GBP", "USD", "CAD");

    private final Account existing;
    private final TextField name = new TextField();
    private final ComboBox<AccountType> type = new ComboBox<>();
    private final TextField initialBalance = new TextField();
    private final ComboBox<String> currency = new ComboBox<>();
    private final DatePicker openingDate;
    private final ColorPicker color = new ColorPicker();
    private final CheckBox includeInAvailable = new CheckBox("Compter ce compte dans le disponible réel et les prévisions");

    public AccountDialog(UiContext ctx, Account existing) {
        super(ctx, existing == null ? "Nouveau compte" : "Modifier le compte", "Enregistrer");
        this.existing = existing;
        type.getItems().setAll(AccountType.values());
        type.setMaxWidth(Double.MAX_VALUE);
        currency.getItems().setAll(CURRENCIES);
        currency.setEditable(true);
        openingDate = Widgets.datePicker(existing == null ? LocalDate.now() : existing.openingDate());
        name.setPromptText("Ex. Compte courant");
        initialBalance.setPromptText("0,00");

        if (existing == null) {
            type.setValue(AccountType.CHECKING);
            currency.setValue(ctx.services().settings().baseCurrency().getCurrencyCode());
            includeInAvailable.setSelected(true);
            color.setValue(Color.web("#DB8D77"));
            // Tant que l'utilisateur n'a pas touche la case, elle suit le type de compte.
            boolean[] touched = {false};
            includeInAvailable.setOnAction(e -> touched[0] = true);
            type.valueProperty().addListener((obs, old, t) -> {
                if (!touched[0] && t != null) {
                    includeInAvailable.setSelected(t.includedInAvailableByDefault());
                }
            });
        } else {
            name.setText(existing.name());
            type.setValue(existing.type());
            initialBalance.setText(AmountParser.toEditable(existing.initialBalance().amount()));
            currency.setValue(existing.currency().getCurrencyCode());
            includeInAvailable.setSelected(existing.includeInAvailable());
            color.setValue(existing.color() != null ? Color.web(existing.color()) : Color.web("#DB8D77"));
        }

        addRow("Nom", name);
        addRow("Type", type);
        addRow("Solde initial", initialBalance);
        addRow("Devise", currency);
        addRow("Date d'ouverture", openingDate);
        addRow("Couleur", color);
        addFullRow(includeInAvailable);
        setOnShown(e -> name.requestFocus());
    }

    @Override
    protected Account submit() {
        BigDecimal initial = initialBalance.getText().isBlank() ? BigDecimal.ZERO : requireAmount(initialBalance, true);
        Currency cur;
        try {
            cur = Currency.getInstance(currency.getValue() == null ? "" : currency.getValue().strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Code devise inconnu (ex. EUR, CHF, USD)");
        }
        LocalDate opening = require(Widgets.dateValue(openingDate), "Date d'ouverture invalide");
        String hex = toHex(color.getValue());
        Account account = new Account(
                existing == null ? null : existing.id(),
                name.getText(),
                require(type.getValue(), "Choisissez un type de compte"),
                Money.of(initial, cur),
                opening,
                existing == null ? null : existing.icon(),
                hex,
                includeInAvailable.isSelected(),
                existing != null && existing.archived(),
                existing == null ? 0 : existing.sortOrder());
        return ctx.services().accounts().save(account);
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }
}
