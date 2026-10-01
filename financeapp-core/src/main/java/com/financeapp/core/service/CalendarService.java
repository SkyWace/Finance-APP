package com.financeapp.core.service;

import com.financeapp.core.calendar.CalendarDay;
import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.forecast.ForecastPoint;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.transaction.Transaction;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Calendrier financier : operations reelles et prevues d'un mois, avec le solde jour par jour. */
public final class CalendarService {

    private final TransactionRepository transactions;
    private final PlanningService planning;
    private final ForecastService forecast;
    private final AccountService accounts;

    public CalendarService(TransactionRepository transactions, PlanningService planning, ForecastService forecast) {
        this(transactions, planning, forecast, null);
    }

    public CalendarService(TransactionRepository transactions, PlanningService planning, ForecastService forecast,
                           AccountService accounts) {
        this.transactions = transactions;
        this.planning = planning;
        this.forecast = forecast;
        this.accounts = accounts;
    }

    /**
     * Mois vu depuis un seul compte ({@code null} : comptes du disponible). Pour un
     * compte, les deux jambes des virements comptent, chacune avec le signe du compte,
     * et le solde affiche est celui du compte ; avant sa derniere valeur saisie
     * (livret, placement), le solde n'est pas reconstituable et reste vide.
     */
    public List<CalendarDay> month(YearMonth month, Long accountId) {
        if (accountId == null || accounts == null) {
            return month(month);
        }
        long id = accountId;
        LocalDate today = planning.today();
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        List<Transaction> counted = transactions.findCounted(first, LocalDate.of(9999, 12, 31)).stream()
                .filter(t -> t.accountId() == id)
                .toList();
        Map<LocalDate, List<Transaction>> realized = counted.stream()
                .filter(t -> !t.date().isAfter(last))
                .collect(Collectors.groupingBy(Transaction::date));
        List<PlannedItem> pending = last.isBefore(today.minusDays(PlanningService.RECURRING_OVERDUE_DAYS))
                ? List.of()
                : planning.upcoming(last).stream().filter(i -> i.accountId() == id).toList();
        Map<LocalDate, List<PlannedItem>> planned = pending.stream()
                .filter(i -> !i.date().isBefore(first))
                .collect(Collectors.groupingBy(PlannedItem::date));

        Money current = accounts.balanceOf(id);
        LocalDate valuedOn = accounts.latestValuation(id).map(v -> v.date()).orElse(null);
        List<CalendarDay> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            LocalDate day = d;
            Money balance;
            boolean projected = !day.isBefore(today);
            if (projected) {
                // Solde reel actuel + operations prevues jusqu'a ce jour (retards compris).
                balance = current;
                for (PlannedItem i : pending) {
                    if (!i.date().isAfter(day) && i.amount().isSameCurrency(current)) {
                        balance = balance.plus(i.amount());
                    }
                }
            } else if (valuedOn != null && day.isBefore(valuedOn)) {
                balance = null;
            } else {
                // Solde de fin de journee : solde actuel moins les operations posterieures.
                balance = current;
                for (Transaction t : counted) {
                    if (t.date().isAfter(day) && t.amount().isSameCurrency(current)) {
                        balance = balance.minus(t.amount());
                    }
                }
            }
            days.add(new CalendarDay(day, realized.getOrDefault(day, List.of()), planned.getOrDefault(day, List.of()),
                    balance, projected));
        }
        return days;
    }

    public List<CalendarDay> month(YearMonth month) {
        LocalDate today = planning.today();
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();

        Map<LocalDate, List<Transaction>> realized = transactions.findCounted(first, last).stream()
                .collect(Collectors.groupingBy(Transaction::date));
        Map<LocalDate, List<PlannedItem>> planned = last.isBefore(today.minusDays(PlanningService.RECURRING_OVERDUE_DAYS))
                ? Map.of()
                : planning.upcomingForDisplay(last).stream()
                        .filter(i -> !i.date().isBefore(first))
                        .collect(Collectors.groupingBy(PlannedItem::date));

        int historyDays = (int) Math.max(0, ChronoUnit.DAYS.between(first, today));
        int projectionDays = (int) Math.max(0, ChronoUnit.DAYS.between(today, last));
        Forecast f = forecast.forecast(historyDays, projectionDays);
        Map<LocalDate, ForecastPoint> points = new HashMap<>();
        f.history().forEach(p -> points.put(p.date(), p));
        // Pour aujourd'hui, la projection integre les operations prevues du jour.
        f.projection().forEach(p -> points.put(p.date(), p));

        List<CalendarDay> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            ForecastPoint p = points.get(d);
            Money balance = p == null ? null : p.balance();
            days.add(new CalendarDay(d, realized.getOrDefault(d, List.of()), planned.getOrDefault(d, List.of()),
                    balance, p != null && p.projected()));
        }
        return days;
    }
}
