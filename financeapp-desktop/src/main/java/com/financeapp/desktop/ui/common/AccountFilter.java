package com.financeapp.desktop.ui.common;

import com.financeapp.core.account.Account;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;

import java.util.Objects;

/**
 * Filtre "compte" partage entre les ecrans (a venir, recurrences, abonnements,
 * analyses...) : le compte choisi reste selectionne quand on change d'ecran.
 * {@code null} = tous les comptes.
 */
public final class AccountFilter {

    private final UiContext ctx;
    private final ComboBox<Choice<Long>> combo = new ComboBox<>();
    private boolean syncing;

    public AccountFilter(UiContext ctx, Runnable onChange) {
        this.ctx = ctx;
        combo.setPrefWidth(240);
        combo.setOnAction(e -> {
            if (syncing) {
                return;
            }
            Long chosen = Widgets.selected(combo);
            if (!Objects.equals(chosen, ctx.accountFilter().get())) {
                ctx.accountFilter().set(chosen);
                onChange.run();
            }
        });
    }

    /** Recharge la liste des comptes actifs et renvoie le compte filtre ({@code null} = tous). */
    public Long sync() {
        syncing = true;
        try {
            // Vider la selection avant de remplacer les elements : sinon, reselectionner un
            // element egal (record) ne rafraichit pas l'affichage et la liste parait vide.
            combo.getSelectionModel().clearSelection();
            combo.getItems().setAll(new Choice<>(null, "Tous les comptes"));
            for (Account a : ctx.services().accounts().findActive()) {
                combo.getItems().add(new Choice<>(a.id(), a.name()));
            }
            Long wanted = ctx.accountFilter().get();
            if (wanted != null && combo.getItems().stream().noneMatch(c -> wanted.equals(c.value()))) {
                ctx.accountFilter().set(null);
            }
            Widgets.select(combo, ctx.accountFilter().get());
            return ctx.accountFilter().get();
        } finally {
            syncing = false;
        }
    }

    public Node node() {
        return Widgets.row(Widgets.label("Compte", "muted"), combo);
    }

    /** Nom du compte filtre, pour les libelles ("pour Livret A"). */
    public String selectedName() {
        return combo.getValue() == null || combo.getValue().value() == null ? null : combo.getValue().label();
    }
}
