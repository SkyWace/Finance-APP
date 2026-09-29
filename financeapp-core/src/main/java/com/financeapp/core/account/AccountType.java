package com.financeapp.core.account;

/** Types de comptes. Le regroupement (courant / epargne) alimente le tableau de bord. */
public enum AccountType {
    CHECKING("Compte courant", Group.CURRENT, true),
    JOINT("Compte joint", Group.CURRENT, true),
    PASSBOOK("Livret", Group.SAVINGS, false),
    SAVINGS("Compte épargne", Group.SAVINGS, false),
    CASH("Espèces", Group.CASH, true),
    PREPAID_CARD("Carte prépayée", Group.CASH, true),
    OTHER("Autre", Group.OTHER, false);

    public enum Group { CURRENT, SAVINGS, CASH, OTHER }

    private final String label;
    private final Group group;
    private final boolean includedInAvailableByDefault;

    AccountType(String label, Group group, boolean includedInAvailableByDefault) {
        this.label = label;
        this.group = group;
        this.includedInAvailableByDefault = includedInAvailableByDefault;
    }

    public String label() {
        return label;
    }

    public Group group() {
        return group;
    }

    /** Valeur proposee a la creation du compte ; l'utilisateur peut la changer. */
    public boolean includedInAvailableByDefault() {
        return includedInAvailableByDefault;
    }

    @Override
    public String toString() {
        return label;
    }
}
