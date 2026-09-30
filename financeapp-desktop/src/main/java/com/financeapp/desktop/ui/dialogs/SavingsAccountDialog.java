package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.service.BusinessException;
import com.financeapp.desktop.ui.common.FormDialog;
import com.financeapp.desktop.ui.common.UiContext;
import com.financeapp.desktop.ui.common.Widgets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;

/**
 * Ajout d'un produit d'epargne detenu (Livret A, LDDS, PEA, assurance-vie,
 * epargne salariale...) avec sa valeur actuelle. Les versements et retraits
 * ulterieurs se saisissent comme des virements ; la valeur peut etre mise a jour.
 */
public final class SavingsAccountDialog extends FormDialog<Account> {

    private final ComboBox<AccountType> type = new ComboBox<>();
    private final TextField name = new TextField();
    private final TextField value = new TextField();
    private final DatePicker date;
    private boolean nameEdited;

    public SavingsAccountDialog(UiContext ctx) {
        super(ctx, "Ajouter une épargne", "Ajouter");
        date = Widgets.datePicker(ctx.services().planning().today());
        type.getItems().setAll(Arrays.stream(AccountType.values()).filter(AccountType::isSavings).toList());
        type.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(AccountType t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : t.label() + "  —  " + t.savings().label().toLowerCase());
            }
        });
        type.setMaxWidth(Double.MAX_VALUE);
        type.getSelectionModel().select(AccountType.LIVRET_A);
        name.setText(shortName(AccountType.LIVRET_A));
        name.textProperty().addListener((o, a, b) -> {
            if (!b.equals(shortName(type.getValue()))) {
                nameEdited = true;
            }
        });
        type.valueProperty().addListener((o, a, t) -> {
            if (!nameEdited && t != null) {
                name.setText(shortName(t));
                nameEdited = false;
            }
        });
        value.setPromptText("Ex. 8 500,00");

        addRow("Produit", type);
        addRow("Nom", name);
        addRow("Valeur actuelle", value);
        addRow("À la date du", date);
        Label hint = Widgets.label("Indiquez le solde ou la valeur figurant sur votre dernier relevé. Ensuite, "
                + "saisissez vos versements et retraits comme des virements depuis votre compte courant, et mettez "
                + "à jour la valeur des placements (PEA, assurance-vie…) quand vous recevez un relevé. L'épargne "
                + "n'est pas comptée dans le disponible réel.", "hint");
        hint.setWrapText(true);
        hint.setMaxWidth(480);
        addFullRow(hint);
        setOnShown(e -> value.requestFocus());
    }

    static String shortName(AccountType t) {
        String label = t.label();
        int paren = label.indexOf(" (");
        return paren > 0 ? label.substring(0, paren) : label;
    }

    @Override
    protected Account submit() {
        AccountType t = require(type.getValue(), "Choisissez le produit d'épargne");
        if (value.getText().isBlank()) {
            throw new BusinessException("Indiquez la valeur actuelle (0 si le produit vient d'être ouvert)");
        }
        BigDecimal amount = requireAmount(value, true);
        if (amount.signum() < 0) {
            throw new BusinessException("La valeur ne peut pas être négative");
        }
        LocalDate day = require(Widgets.dateValue(date), "Date invalide");
        if (day.isAfter(ctx.services().planning().today())) {
            throw new BusinessException("La date ne peut pas être dans le futur");
        }
        if (name.getText().isBlank()) {
            throw new BusinessException("Le nom est obligatoire");
        }
        var currency = ctx.services().settings().baseCurrency();
        return ctx.services().accounts().save(Account.create(name.getText(), t, Money.of(amount, currency), day));
    }
}
