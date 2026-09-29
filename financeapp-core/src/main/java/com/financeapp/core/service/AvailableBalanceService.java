package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.available.AccountBalance;
import com.financeapp.core.available.AvailableBalanceEngine;
import com.financeapp.core.available.AvailableBalanceInput;
import com.financeapp.core.available.AvailableBalanceResult;
import com.financeapp.core.available.Horizon;
import com.financeapp.core.available.HorizonType;
import com.financeapp.core.available.Reservation;
import com.financeapp.core.available.ReservationProvider;
import com.financeapp.core.settings.SettingsService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestration du disponible reel : resout l'echeance, rassemble soldes et
 * operations a venir du perimetre "disponible", puis delegue le calcul a
 * {@link AvailableBalanceEngine}.
 */
public final class AvailableBalanceService {

    private final AccountService accounts;
    private final PlanningService planning;
    private final RecurringService recurring;
    private final SettingsService settings;
    private final List<ReservationProvider> reservationProviders;
    private final AvailableBalanceEngine engine = new AvailableBalanceEngine();

    public AvailableBalanceService(AccountService accounts, PlanningService planning,
                                   RecurringService recurring, SettingsService settings) {
        this(accounts, planning, recurring, settings, List.of());
    }

    /** @param reservationProviders budgets, objectifs d'epargne... dont le reste est mis de cote */
    public AvailableBalanceService(AccountService accounts, PlanningService planning, RecurringService recurring,
                                   SettingsService settings, List<ReservationProvider> reservationProviders) {
        this.accounts = accounts;
        this.planning = planning;
        this.recurring = recurring;
        this.settings = settings;
        this.reservationProviders = List.copyOf(reservationProviders);
    }

    /** Comptes du perimetre : actifs, dans la devise de reference, marques "inclus dans le disponible". */
    public List<AccountBalance> scope() {
        return accounts.balancesOf(settings.baseCurrency(), Account::includeInAvailable);
    }

    public Horizon resolve(HorizonType type, LocalDate customDate) {
        LocalDate today = planning.today();
        return switch (type) {
            case END_OF_WEEK -> new Horizon(type, today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), null);
            case END_OF_MONTH -> new Horizon(type, today.with(TemporalAdjusters.lastDayOfMonth()), null);
            case CUSTOM_DATE -> new Horizon(type, customDate == null || customDate.isBefore(today) ? today : customDate, null);
            case NEXT_PAYDAY -> {
                Set<Long> ids = scope().stream().map(AccountBalance::accountId).collect(Collectors.toSet());
                // La paie elle-meme n'est pas comptee : on calcule ce qui doit tenir jusqu'a la veille.
                yield recurring.nextPayday(ids)
                        .map(payday -> new Horizon(type, payday.minusDays(1), payday))
                        .orElseGet(() -> new Horizon(type, today.with(TemporalAdjusters.lastDayOfMonth()), null));
            }
        };
    }

    public AvailableBalanceResult compute(HorizonType type, LocalDate customDate) {
        return compute(resolve(type, customDate));
    }

    public AvailableBalanceResult computeDefault() {
        return compute(settings.defaultHorizon(), null);
    }

    public AvailableBalanceResult compute(Horizon horizon) {
        LocalDate today = planning.today();
        List<Reservation> reservations = new ArrayList<>();
        for (ReservationProvider provider : reservationProviders) {
            reservations.addAll(provider.reservations(settings.baseCurrency(), today, horizon.end()));
        }
        reservations.removeIf(r -> r.amount().isZero());
        AvailableBalanceInput input = new AvailableBalanceInput(
                settings.baseCurrency(),
                today,
                horizon.end(),
                scope(),
                planning.upcoming(horizon.end()),
                reservations,
                settings.includeCertainIncome());
        return engine.compute(input);
    }
}
