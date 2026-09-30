package com.financeapp.core.available;

import com.financeapp.core.available.AvailableBalanceResult.Line;
import com.financeapp.core.available.AvailableBalanceResult.Section;
import com.financeapp.core.available.AvailableBalanceResult.SectionKind;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Moteur du "disponible reel" :
 *
 * <pre>
 *   solde actuel des comptes du perimetre
 * - depenses prevues jusqu'a l'echeance (y compris celles en retard)
 * +/- virements prevus avec des comptes hors perimetre (epargne)
 * - reservations (budgets restants, objectifs d'epargne)
 * + revenus prevus juges certains (si l'option est active)
 * = disponible reel
 * </pre>
 *
 * Un virement entre deux comptes du perimetre est neutre et ignore. Moteur
 * pur : aucune I/O, aucun etat, resultat entierement explicable.
 */
public final class AvailableBalanceEngine {

    public AvailableBalanceResult compute(AvailableBalanceInput input) {
        Money zero = Money.zero(input.currency());
        Set<Long> scope = input.balances().stream().map(AccountBalance::accountId).collect(Collectors.toSet());
        Map<SectionKind, List<Line>> lines = new EnumMap<>(SectionKind.class);

        for (AccountBalance b : input.balances()) {
            add(lines, SectionKind.CURRENT_BALANCE, new Line(b.accountName(), null, b.balance(), false));
        }

        for (PlannedItem item : input.plannedItems()) {
            if (!scope.contains(item.accountId()) || item.date().isAfter(input.horizonEnd())) {
                continue;
            }
            Line line = new Line(item.label(), item.date(), item.amount(), item.isOverdue(input.today()));
            switch (item.type()) {
                case EXPENSE -> add(lines, SectionKind.PLANNED_EXPENSES, line);
                case TRANSFER -> {
                    if (item.transferAccountId() == null || !scope.contains(item.transferAccountId())) {
                        add(lines, SectionKind.SAVINGS_TRANSFERS, line);
                    }
                }
                case INCOME -> add(lines,
                        input.includeCertainIncome() && item.certain()
                                ? SectionKind.EXPECTED_INCOME : SectionKind.EXCLUDED_INCOME,
                        line);
            }
        }

        for (Reservation r : input.reservations()) {
            add(lines, SectionKind.RESERVATIONS, new Line(r.label(), null, r.amount().negate(), false));
        }

        List<Section> sections = new ArrayList<>();
        Money available = zero;
        Money reservations = zero;
        for (SectionKind kind : SectionKind.values()) {
            List<Line> sectionLines = new ArrayList<>(lines.getOrDefault(kind, List.of()));
            if (sectionLines.isEmpty() && kind != SectionKind.CURRENT_BALANCE) {
                continue;
            }
            sectionLines.sort(Comparator.comparing(Line::date, Comparator.nullsFirst(Comparator.naturalOrder())));
            Money total = sectionLines.stream().map(Line::amount).reduce(zero, Money::plus);
            boolean counted = kind != SectionKind.EXCLUDED_INCOME;
            sections.add(new Section(kind, total, counted, List.copyOf(sectionLines)));
            if (counted) {
                available = available.plus(total);
            }
            if (kind == SectionKind.RESERVATIONS) {
                reservations = total;
            }
        }
        return new AvailableBalanceResult(input.today(), input.horizonEnd(), available,
                available.minus(reservations), List.copyOf(sections));
    }

    private static void add(Map<SectionKind, List<Line>> lines, SectionKind kind, Line line) {
        lines.computeIfAbsent(kind, k -> new ArrayList<>()).add(line);
    }
}
