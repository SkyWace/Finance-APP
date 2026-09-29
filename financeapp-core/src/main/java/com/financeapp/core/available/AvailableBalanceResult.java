package com.financeapp.core.available;

import com.financeapp.core.money.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Resultat explicable du disponible reel : chaque montant ajoute ou deduit
 * figure dans une ligne d'une section, et
 * {@code available = somme des totaux des sections comptees}.
 *
 * @param availableBeforeReservations disponible si aucune depense variable (budgets, objectifs) n'etait reservee
 */
public record AvailableBalanceResult(
        LocalDate today,
        LocalDate horizonEnd,
        Money available,
        Money availableBeforeReservations,
        List<Section> sections) {

    public enum SectionKind {
        CURRENT_BALANCE("Solde actuel"),
        PLANNED_EXPENSES("Dépenses prévues"),
        SAVINGS_TRANSFERS("Épargne et virements prévus"),
        RESERVATIONS("Budgets et objectifs réservés"),
        EXPECTED_INCOME("Revenus prévus"),
        EXCLUDED_INCOME("Revenus non comptés");

        private final String title;

        SectionKind(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /**
     * @param counted {@code false} pour une section purement informative (ex. revenus exclus du calcul)
     */
    public record Section(SectionKind kind, Money total, boolean counted, List<Line> lines) {
    }

    /** @param date {@code null} pour une ligne non datee (solde d'un compte, reservation) */
    public record Line(String label, LocalDate date, Money amount, boolean overdue) {
    }

    public Optional<Section> section(SectionKind kind) {
        return sections.stream().filter(s -> s.kind() == kind).findFirst();
    }

    public Money total(SectionKind kind) {
        return section(kind).map(Section::total).orElse(Money.zero(available.currency()));
    }
}
