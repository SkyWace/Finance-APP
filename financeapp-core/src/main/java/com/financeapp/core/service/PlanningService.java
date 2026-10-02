package com.financeapp.core.service;

import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Liste unifiee des operations a venir : transactions "prevues" (y compris
 * en retard) + occurrences de recurrences non traitees.
 */
public final class PlanningService {

    /**
     * Les occurrences recurrentes non validees restent "a venir" (en retard)
     * pendant ce nombre de jours, puis sont considerees comme obsoletes. Evite
     * qu'une regle creee avec une date de depart ancienne ne genere des
     * dizaines d'occurrences fantomes.
     */
    public static final int RECURRING_OVERDUE_DAYS = 14;

    private final TransactionRepository transactions;
    private final RecurringService recurring;
    private final Clock clock;

    public PlanningService(TransactionRepository transactions, RecurringService recurring, Clock clock) {
        this.transactions = transactions;
        this.recurring = recurring;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Operations a venir jusqu'a {@code until} (inclus), retards compris, triees par date. */
    public List<PlannedItem> upcoming(LocalDate until) {
        LocalDate today = today();
        List<PlannedItem> items = new ArrayList<>();
        for (Transaction t : transactions.findPlannedUntil(until)) {
            items.add(new PlannedItem(t.date(), t.accountId(), t.label(), t.amount(), t.type(), t.categoryId(),
                    PlannedItem.Source.PLANNED_TRANSACTION, t.id(), null, t.transferAccountId(), true, t.splits()));
        }
        items.addAll(recurring.pendingOccurrences(today.minusDays(RECURRING_OVERDUE_DAYS), until));
        items.sort(Comparator.comparing(PlannedItem::date).thenComparing(i -> i.amount().amount()));
        return items;
    }

    /**
     * Operations a venir vues globalement : une seule ligne par virement (la
     * jambe debitrice), pour les listes affichees a l'utilisateur.
     */
    public List<PlannedItem> upcomingForDisplay(LocalDate until) {
        return upcoming(until).stream()
                .filter(i -> i.type() != TransactionType.TRANSFER || i.amount().isNegative())
                .toList();
    }
}
