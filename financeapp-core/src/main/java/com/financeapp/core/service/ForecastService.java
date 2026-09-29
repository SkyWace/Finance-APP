package com.financeapp.core.service;

import com.financeapp.core.available.AccountBalance;
import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.forecast.ForecastEngine;
import com.financeapp.core.forecast.ForecastInput;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Prevision du solde du perimetre "disponible" (comptes courants par defaut). */
public final class ForecastService {

    private final AvailableBalanceService available;
    private final PlanningService planning;
    private final TransactionRepository transactions;
    private final com.financeapp.core.settings.SettingsService settings;
    private final ForecastEngine engine = new ForecastEngine();

    public ForecastService(AvailableBalanceService available, PlanningService planning,
                           TransactionRepository transactions, com.financeapp.core.settings.SettingsService settings) {
        this.available = available;
        this.planning = planning;
        this.transactions = transactions;
        this.settings = settings;
    }

    /**
     * @param historyDays nombre de jours d'historique reel avant aujourd'hui
     * @param days        nombre de jours de projection apres aujourd'hui
     */
    public Forecast forecast(int historyDays, int days) {
        LocalDate today = planning.today();
        List<AccountBalance> scope = available.scope();
        Set<Long> ids = scope.stream().map(AccountBalance::accountId).collect(Collectors.toSet());
        Money current = scope.stream().map(AccountBalance::balance)
                .reduce(Money.zero(settings.baseCurrency()), Money::plus);
        LocalDate historyFrom = today.minusDays(Math.max(0, historyDays));
        LocalDate until = today.plusDays(Math.max(0, days));
        ForecastInput input = new ForecastInput(ids, current, today, historyFrom,
                transactions.findCounted(historyFrom.plusDays(1), LocalDate.MAX),
                until, planning.upcoming(until));
        return engine.compute(input);
    }
}
