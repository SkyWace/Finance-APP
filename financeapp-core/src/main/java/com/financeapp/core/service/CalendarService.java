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

    public CalendarService(TransactionRepository transactions, PlanningService planning, ForecastService forecast) {
        this.transactions = transactions;
        this.planning = planning;
        this.forecast = forecast;
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
