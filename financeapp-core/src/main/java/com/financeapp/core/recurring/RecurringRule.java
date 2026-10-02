package com.financeapp.core.recurring;

import com.financeapp.core.money.Money;
import com.financeapp.core.transaction.TransactionType;

import com.financeapp.core.transaction.SplitLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Regle d'operation recurrente. Ses occurrences ne sont PAS stockees : elles
 * sont calculees a la demande par {@link RecurrenceEngine}. Seules les
 * occurrences validees (ou ignorees) deviennent des transactions.
 *
 * @param amount      montant toujours positif ; le sens est donne par {@code type}
 * @param toAccountId compte destinataire pour un virement recurrent (ex. epargne mensuelle)
 * @param certain     revenu juge suffisamment certain pour etre compte dans le disponible reel
 * @param endDate     derniere date possible (incluse), {@code null} = sans fin
 * @param trackedFrom date a partir de laquelle les occurrences sont suivies (en general la date de
 *                    creation de la regle) : les occurrences anterieures sont reputees deja traitees,
 *                    elles n'apparaissent jamais comme "en retard"
 * @param splits      ventilation sur plusieurs categories (montants positifs, somme = {@code amount}) ;
 *                    vide : une seule categorie, {@code categoryId}
 */
public record RecurringRule(
        Long id,
        long accountId,
        Long toAccountId,
        TransactionType type,
        String label,
        Money amount,
        Long categoryId,
        Frequency frequency,
        int interval,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate trackedFrom,
        boolean certain,
        boolean active,
        String note,
        List<SplitLine> splits) {

    /** Regle sans ventilation. */
    public RecurringRule(Long id, long accountId, Long toAccountId, TransactionType type, String label, Money amount,
                         Long categoryId, Frequency frequency, int interval, LocalDate startDate, LocalDate endDate,
                         LocalDate trackedFrom, boolean certain, boolean active, String note) {
        this(id, accountId, toAccountId, type, label, amount, categoryId, frequency, interval, startDate, endDate,
                trackedFrom, certain, active, note, List.of());
    }

    public RecurringRule {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(startDate, "startDate");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Le libellé est obligatoire");
        }
        label = label.strip();
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Le montant d'une récurrence doit être positif");
        }
        if (interval < 1) {
            throw new IllegalArgumentException("L'intervalle doit être supérieur ou égal à 1");
        }
        if (!frequency.isCustom()) {
            interval = 1;
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("La date de fin précède la date de début");
        }
        if (trackedFrom == null) {
            trackedFrom = startDate;
        }
        if (type == TransactionType.TRANSFER) {
            if (toAccountId == null || toAccountId == accountId) {
                throw new IllegalArgumentException("Un virement récurrent nécessite un compte destinataire différent");
            }
        } else if (toAccountId != null) {
            throw new IllegalArgumentException("Seul un virement a un compte destinataire");
        }
        splits = splits == null ? List.of() : List.copyOf(splits);
        if (!splits.isEmpty()) {
            if (type == TransactionType.TRANSFER) {
                throw new IllegalArgumentException("Un virement récurrent ne se ventile pas");
            }
            if (splits.size() < 2) {
                throw new IllegalArgumentException("Une ventilation comporte au moins deux lignes");
            }
            Money total = Money.zero(amount.currency());
            for (SplitLine line : splits) {
                if (!line.amount().isSameCurrency(amount) || !line.amount().isPositive()) {
                    throw new IllegalArgumentException("Les lignes de ventilation d'une récurrence sont positives, "
                            + "dans la devise de la récurrence");
                }
                total = total.plus(line.amount());
            }
            if (!total.equals(amount)) {
                throw new IllegalArgumentException("La somme des lignes de ventilation doit être égale au montant");
            }
            categoryId = null; // portee par chaque ligne
        }
    }

    public boolean isSplit() {
        return !splits.isEmpty();
    }

    /**
     * Ventilation signee pour un montant reel donne (positif) : identique a celle de la
     * regle si le montant n'a pas change, sinon repartie proportionnellement (la derniere
     * ligne absorbe l'arrondi). Vide si la regle n'est pas ventilee, ou si un ajustement
     * rendait une ligne nulle.
     */
    public List<SplitLine> signedSplitsFor(Money actual) {
        if (!isSplit()) {
            return List.of();
        }
        boolean negative = type == TransactionType.EXPENSE;
        List<SplitLine> result = new ArrayList<>();
        if (actual.equals(amount)) {
            for (SplitLine line : splits) {
                result.add(new SplitLine(line.categoryId(), negative ? line.amount().negate() : line.amount()));
            }
            return result;
        }
        int digits = Math.max(0, amount.currency().getDefaultFractionDigits());
        Money allocated = Money.zero(actual.currency());
        for (int i = 0; i < splits.size(); i++) {
            SplitLine line = splits.get(i);
            Money part = i == splits.size() - 1 ? actual.minus(allocated)
                    : Money.of(actual.amount().multiply(line.amount().amount())
                            .divide(amount.amount(), digits, RoundingMode.HALF_EVEN), actual.currency());
            if (!part.isPositive()) {
                return List.of();
            }
            allocated = allocated.plus(part);
            result.add(new SplitLine(line.categoryId(), negative ? part.negate() : part));
        }
        return result;
    }

    /** Categorie principale : celle de la regle, ou celle de la plus grosse ligne de ventilation. */
    public Long mainCategoryId() {
        if (!isSplit()) {
            return categoryId;
        }
        return splits.stream().max(java.util.Comparator.comparing(l -> l.amount().amount())).orElseThrow().categoryId();
    }

    /**
     * Equivalent mensuel de la seule part de la regle comprise dans les categories donnees
     * (toute la regle si sa categorie y est, une partie si elle est ventilee).
     */
    public Money monthlyEquivalentIn(Set<Long> categoryScope) {
        Money monthly = monthlyEquivalent();
        if (!isSplit()) {
            return categoryId != null && categoryScope.contains(categoryId) ? monthly : Money.zero(amount.currency());
        }
        BigDecimal inScope = splits.stream().filter(l -> l.categoryId() != null && categoryScope.contains(l.categoryId()))
                .map(l -> l.amount().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Money.of(monthly.amount().multiply(inScope).divide(amount.amount(), 2, RoundingMode.HALF_EVEN),
                amount.currency());
    }

    /** Montant signe tel qu'il s'applique au compte source. */
    public Money signedAmount() {
        return type == TransactionType.INCOME ? amount : amount.negate();
    }

    /** Equivalent mensuel moyen du montant signe (ex. 120 EUR/an -> 10 EUR/mois). */
    public Money monthlyEquivalent() {
        return signedAmount().multiply(frequency.occurrencesPerYear(interval)).divide(java.math.BigDecimal.valueOf(12));
    }

    public RecurringRule withId(long newId) {
        return new RecurringRule(newId, accountId, toAccountId, type, label, amount, categoryId, frequency,
                interval, startDate, endDate, trackedFrom, certain, active, note, splits);
    }

    public RecurringRule withTrackedFrom(LocalDate date) {
        return new RecurringRule(id, accountId, toAccountId, type, label, amount, categoryId, frequency,
                interval, startDate, endDate, date, certain, active, note, splits);
    }

    public RecurringRule withEndDate(LocalDate newEndDate) {
        return new RecurringRule(id, accountId, toAccountId, type, label, amount, categoryId, frequency,
                interval, startDate, newEndDate, trackedFrom, certain, active, note, splits);
    }
}
