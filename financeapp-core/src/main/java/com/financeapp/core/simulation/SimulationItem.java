package com.financeapp.core.simulation;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Hypothese d'une simulation. Rien n'est jamais enregistre comme operation reelle.
 *
 * <ul>
 *   <li>{@code ONE_TIME} : montant signe (negatif = depense) a une date (ex. apport de 4 000 EUR).</li>
 *   <li>{@code MONTHLY} : montant signe chaque mois a partir de {@code date}, pendant {@code months} mois
 *       ({@code null} = jusqu'a la fin de la simulation) (ex. assurance 100 EUR/mois).</li>
 *   <li>{@code LOAN} : credit ; {@code amount} = capital emprunte, premiere mensualite a {@code date},
 *       {@code months} mensualites, {@code annualRate} et/ou {@code payment}. Le capital est suppose verse
 *       au vendeur : seules les mensualites sortent des comptes.</li>
 *   <li>{@code STOP_RECURRING} : arret d'une recurrence existante a partir de {@code date}
 *       (ex. resiliation d'un abonnement).</li>
 * </ul>
 */
public record SimulationItem(
        Long id,
        Kind kind,
        String label,
        Money amount,
        LocalDate date,
        Integer months,
        BigDecimal annualRate,
        Money payment,
        Long recurringId) {

    public enum Kind {
        ONE_TIME("Dépense ou rentrée ponctuelle"),
        MONTHLY("Montant mensuel"),
        LOAN("Crédit"),
        STOP_RECURRING("Arrêt d'une récurrence");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public SimulationItem {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(date, "date");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Le libellé de l'hypothèse est obligatoire");
        }
        label = label.strip();
        if (months != null && (months < 1 || months > 600)) {
            throw new IllegalArgumentException("La durée doit être comprise entre 1 et 600 mois");
        }
        switch (kind) {
            case ONE_TIME, MONTHLY -> {
                if (amount == null || amount.isZero()) {
                    throw new IllegalArgumentException("Le montant de l'hypothèse est obligatoire");
                }
            }
            case LOAN -> {
                if (amount == null || !amount.isPositive()) {
                    throw new IllegalArgumentException("Le capital emprunté doit être strictement positif");
                }
                if (months == null) {
                    throw new IllegalArgumentException("La durée du crédit est obligatoire");
                }
                if (annualRate == null && payment == null) {
                    throw new IllegalArgumentException("Indiquez le taux ou la mensualité du crédit");
                }
            }
            case STOP_RECURRING -> {
                if (recurringId == null) {
                    throw new IllegalArgumentException("Choisissez la récurrence à arrêter");
                }
            }
        }
    }
}
