package com.financeapp.core.available;

import com.financeapp.core.money.Money;

/** Solde actuel d'un compte, entree des moteurs de calcul. */
public record AccountBalance(long accountId, String accountName, Money balance) {
}
