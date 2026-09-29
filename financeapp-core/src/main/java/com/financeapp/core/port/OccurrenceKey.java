package com.financeapp.core.port;

import java.time.LocalDate;

/** Identifie une occurrence de regle recurrente deja materialisee (validee ou ignoree). */
public record OccurrenceKey(long recurringId, LocalDate occurrenceDate) {
}
