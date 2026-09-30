package com.financeapp.core.account;

import com.financeapp.core.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Valeur constatee d'un compte a une date (releve annuel d'un livret, valeur d'un
 * PEA ou d'une assurance-vie...). Le solde du compte devient cette valeur, plus
 * les operations posterieures. Une plus-value n'est pas un revenu : elle
 * n'apparait que dans le patrimoine.
 */
public record AccountValuation(Long id, long accountId, LocalDate date, Money value) {

    public AccountValuation {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(value, "value");
    }

    public AccountValuation withId(long newId) {
        return new AccountValuation(newId, accountId, date, value);
    }
}
