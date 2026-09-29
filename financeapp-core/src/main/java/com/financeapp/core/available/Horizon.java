package com.financeapp.core.available;

import java.time.LocalDate;

/**
 * Echeance resolue.
 *
 * @param end    derniere date prise en compte (incluse)
 * @param payday date de la prochaine paie quand {@code type == NEXT_PAYDAY} et qu'elle est connue
 */
public record Horizon(HorizonType type, LocalDate end, LocalDate payday) {
}
