package com.financeapp.core.available;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

/** Source de sommes a mettre de cote dans le calcul du disponible reel (budgets, objectifs d'epargne...). */
public interface ReservationProvider {

    List<Reservation> reservations(Currency currency, LocalDate today, LocalDate horizonEnd);
}
