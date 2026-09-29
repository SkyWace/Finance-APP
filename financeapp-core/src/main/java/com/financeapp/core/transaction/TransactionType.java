package com.financeapp.core.transaction;

/**
 * Nature d'une operation. Un virement interne n'est ni un revenu ni une
 * depense : il est exclu de ces agregats par son type, pas par son signe.
 */
public enum TransactionType {
    INCOME("Revenu"),
    EXPENSE("Dépense"),
    TRANSFER("Virement interne");

    private final String label;

    TransactionType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
