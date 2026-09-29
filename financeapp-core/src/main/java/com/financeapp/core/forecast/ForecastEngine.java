package com.financeapp.core.forecast;

import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Evolution previsionnelle du solde d'un perimetre de comptes.
 *
 * <ul>
 *   <li>Historique : reconstruit a rebours depuis le solde actuel en retirant
 *       les operations reelles posterieures a chaque jour.</li>
 *   <li>Projection : solde actuel + operations a venir cumulees jour par jour ;
 *       les operations en retard (date passee, non validees) sont appliquees
 *       aujourd'hui.</li>
 * </ul>
 * Les virements entre deux comptes du perimetre sont neutres et ignores.
 */
public final class ForecastEngine {

    public Forecast compute(ForecastInput in) {
        Money zero = Money.zero(in.currentBalance().currency());

        // --- Historique reel -------------------------------------------------
        TreeMap<LocalDate, Money> realizedByDay = new TreeMap<>();
        for (Transaction t : in.realized()) {
            if (!in.scope().contains(t.accountId()) || isInternal(t.type(), t.transferAccountId(), in)) {
                continue;
            }
            if (t.date().isBefore(in.historyFrom())) {
                continue;
            }
            realizedByDay.merge(t.date(), t.amount(), Money::plus);
        }
        List<ForecastPoint> history = new ArrayList<>();
        Money balance = in.currentBalance();
        // Operations reelles datees dans le futur (rare) : deja dans le solde actuel.
        for (Money m : realizedByDay.tailMap(in.today(), false).values()) {
            balance = balance.minus(m);
        }
        for (LocalDate d = in.today(); !d.isBefore(in.historyFrom()); d = d.minusDays(1)) {
            history.add(new ForecastPoint(d, balance, false));
            balance = balance.minus(realizedByDay.getOrDefault(d, zero));
        }
        history = new ArrayList<>(history.reversed());
        Money todayEnd = history.getLast().balance();

        // --- Projection ------------------------------------------------------
        TreeMap<LocalDate, Money> plannedByDay = new TreeMap<>();
        for (PlannedItem p : in.planned()) {
            if (!in.scope().contains(p.accountId()) || isInternal(p.type(), p.transferAccountId(), in)
                    || p.date().isAfter(in.until())) {
                continue;
            }
            LocalDate day = p.date().isBefore(in.today()) ? in.today() : p.date();
            plannedByDay.merge(day, p.amount(), Money::plus);
        }
        List<ForecastPoint> projection = new ArrayList<>();
        balance = todayEnd;
        ForecastPoint lowest = null;
        ForecastPoint firstNegative = null;
        for (LocalDate d = in.today(); !d.isAfter(in.until()); d = d.plusDays(1)) {
            balance = balance.plus(plannedByDay.getOrDefault(d, zero));
            ForecastPoint point = new ForecastPoint(d, balance, true);
            projection.add(point);
            if (lowest == null || balance.compareTo(lowest.balance()) < 0) {
                lowest = point;
            }
            if (firstNegative == null && balance.isNegative()) {
                firstNegative = point;
            }
        }
        return new Forecast(List.copyOf(history), List.copyOf(projection), lowest, Optional.ofNullable(firstNegative));
    }

    private static boolean isInternal(TransactionType type, Long transferAccountId, ForecastInput in) {
        return type == TransactionType.TRANSFER && transferAccountId != null && in.scope().contains(transferAccountId);
    }
}
