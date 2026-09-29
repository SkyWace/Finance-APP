package com.financeapp.core.available;

/** Echeance jusqu'a laquelle le disponible reel est calcule. */
public enum HorizonType {
    END_OF_WEEK("Fin de semaine"),
    NEXT_PAYDAY("Prochaine paie"),
    END_OF_MONTH("Fin du mois"),
    CUSTOM_DATE("Date personnalisée");

    private final String label;

    HorizonType(String label) {
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
