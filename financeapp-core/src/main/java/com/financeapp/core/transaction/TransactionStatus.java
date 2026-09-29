package com.financeapp.core.transaction;

/** Cycle de vie d'une operation. */
public enum TransactionStatus {
    PLANNED("Prévu"),
    PENDING("En attente"),
    COMPLETED("Effectué"),
    CANCELLED("Annulé");

    private final String label;

    TransactionStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Une operation en attente est deja engagee (typiquement un paiement
     * carte autorise mais pas encore debite) : elle compte dans le solde
     * actuel, comme une operation effectuee.
     */
    public boolean countsInBalance() {
        return this == PENDING || this == COMPLETED;
    }

    @Override
    public String toString() {
        return label;
    }
}
