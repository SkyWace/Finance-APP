package com.financeapp.core.category;

/** Sens des operations qu'une categorie accepte. */
public enum CategoryKind {
    EXPENSE("Dépense"),
    INCOME("Revenu"),
    BOTH("Mixte");

    private final String label;

    CategoryKind(String label) {
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
