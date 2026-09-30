package com.financeapp.core.account;

import java.math.BigDecimal;

/**
 * Types de comptes. Le regroupement (courant / epargne) alimente le tableau de
 * bord ; les produits d'epargne portent en plus leur liquidite, leur mode de
 * suivi et, pour les livrets reglementes, leur plafond de versements.
 */
public enum AccountType {
    CHECKING("Compte courant", Group.CURRENT, true),
    JOINT("Compte joint", Group.CURRENT, true),
    CASH("Espèces", Group.CASH, true),
    PREPAID_CARD("Carte prépayée", Group.CASH, true),

    // Epargne disponible a tout moment
    LIVRET_A("Livret A", Savings.AVAILABLE, "22950", false),
    LDDS("LDDS (développement durable et solidaire)", Savings.AVAILABLE, "12000", false),
    LEP("LEP (épargne populaire)", Savings.AVAILABLE, "10000", false),
    LIVRET_JEUNE("Livret Jeune", Savings.AVAILABLE, "1600", false),
    CEL("CEL (compte épargne logement)", Savings.AVAILABLE, "15300", false),
    PASSBOOK("Livret bancaire", Savings.AVAILABLE, null, false),
    SAVINGS("Compte épargne", Savings.AVAILABLE, null, false),

    // Epargne a moyen et long terme (valeur mise a jour a la main pour les placements)
    PEL("PEL (plan épargne logement)", Savings.LONG_TERM, "61200", false),
    LIFE_INSURANCE("Assurance-vie", Savings.LONG_TERM, null, true),
    PEA("PEA (plan d'épargne en actions)", Savings.LONG_TERM, null, true),
    PER("PER (plan d'épargne retraite)", Savings.LONG_TERM, null, true),
    EMPLOYEE_SAVINGS("Épargne salariale (PEE, PERCOL…)", Savings.LONG_TERM, null, true),
    SECURITIES("Compte-titres", Savings.LONG_TERM, null, true),
    CRYPTO("Crypto-actifs", Savings.LONG_TERM, null, true),

    OTHER("Autre", Group.OTHER, false);

    public enum Group { CURRENT, SAVINGS, CASH, OTHER }

    /** Liquidite d'un produit d'epargne. */
    public enum Savings {
        AVAILABLE("Épargne disponible"),
        LONG_TERM("Épargne à moyen et long terme");

        private final String label;

        Savings(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String label;
    private final Group group;
    private final boolean includedInAvailableByDefault;
    private final Savings savings;
    private final BigDecimal depositCeiling;
    private final boolean marketValued;

    AccountType(String label, Group group, boolean includedInAvailableByDefault) {
        this.label = label;
        this.group = group;
        this.includedInAvailableByDefault = includedInAvailableByDefault;
        this.savings = null;
        this.depositCeiling = null;
        this.marketValued = false;
    }

    AccountType(String label, Savings savings, String depositCeiling, boolean marketValued) {
        this.label = label;
        this.group = Group.SAVINGS;
        this.includedInAvailableByDefault = false;
        this.savings = savings;
        this.depositCeiling = depositCeiling == null ? null : new BigDecimal(depositCeiling);
        this.marketValued = marketValued;
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

    public boolean isSavings() {
        return savings != null;
    }

    /** Liquidite ({@code null} hors epargne). */
    public Savings savings() {
        return savings;
    }

    /**
     * Plafond reglementaire de versements (hors interets capitalises), en unites de la
     * devise ; {@code null} si aucun. Valeurs indicatives, fixees par les pouvoirs publics.
     */
    public BigDecimal depositCeiling() {
        return depositCeiling;
    }

    /** Placement dont la valeur fluctue : elle est mise a jour par des valorisations. */
    public boolean marketValued() {
        return marketValued;
    }

    @Override
    public String toString() {
        return label;
    }
}
