package com.financeapp.core.budget;

/**
 * Etat d'un budget. Chaque etat a un libelle et un symbole : l'information
 * ne repose jamais sur la seule couleur.
 */
public enum BudgetStatus {
    OK("Dans le budget", "✓"),
    WARNING("Proche de la limite", "!"),
    REACHED("Budget atteint", "■"),
    EXCEEDED("Budget dépassé", "✕");

    /** Seuil (en %) a partir duquel un budget est "proche de la limite". */
    public static final int WARNING_PERCENT = 80;

    private final String label;
    private final String symbol;

    BudgetStatus(String label, String symbol) {
        this.label = label;
        this.symbol = symbol;
    }

    public String label() {
        return label;
    }

    public String symbol() {
        return symbol;
    }
}
