package com.financeapp.core.imports;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Ligne lue dans un fichier bancaire, avant toute decision d'import.
 *
 * @param line       numero de ligne (ou d'operation) dans le fichier, pour les messages
 * @param amount     montant signe (negatif = sortie d'argent)
 * @param externalId identifiant fourni par la banque (FITID en OFX), {@code null} sinon
 * @param error      probleme de lecture ; la ligne ne peut alors pas etre importee
 */
public record ImportedRow(int line, LocalDate date, String label, BigDecimal amount, String externalId, String error) {

    public static ImportedRow invalid(int line, String label, String error) {
        return new ImportedRow(line, null, label, null, null, error);
    }

    public boolean isValid() {
        return error == null;
    }
}
