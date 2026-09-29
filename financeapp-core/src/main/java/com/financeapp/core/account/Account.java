package com.financeapp.core.account;

import com.financeapp.core.money.Money;

import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;

/**
 * Compte suivi par l'utilisateur. La devise est portee par le solde initial.
 *
 * @param id                  identifiant, {@code null} tant que le compte n'est pas enregistre
 * @param includeInAvailable  compte pris en compte dans le calcul du disponible reel et des previsions
 * @param archived            un compte archive reste dans l'historique mais disparait des saisies et totaux
 */
public record Account(
        Long id,
        String name,
        AccountType type,
        Money initialBalance,
        LocalDate openingDate,
        String icon,
        String color,
        boolean includeInAvailable,
        boolean archived,
        int sortOrder) {

    public Account {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(initialBalance, "initialBalance");
        Objects.requireNonNull(openingDate, "openingDate");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Le nom du compte est obligatoire");
        }
        name = name.strip();
    }

    public static Account create(String name, AccountType type, Money initialBalance, LocalDate openingDate) {
        return new Account(null, name, type, initialBalance, openingDate, null, null,
                type.includedInAvailableByDefault(), false, 0);
    }

    public Currency currency() {
        return initialBalance.currency();
    }

    public Account withId(long newId) {
        return new Account(newId, name, type, initialBalance, openingDate, icon, color, includeInAvailable, archived, sortOrder);
    }

    public Account withArchived(boolean value) {
        return new Account(id, name, type, initialBalance, openingDate, icon, color, includeInAvailable, value, sortOrder);
    }

    public Account withInitialBalance(Money value) {
        return new Account(id, name, type, value, openingDate, icon, color, includeInAvailable, archived, sortOrder);
    }

    public Account withIncludeInAvailable(boolean value) {
        return new Account(id, name, type, initialBalance, openingDate, icon, color, value, archived, sortOrder);
    }
}
