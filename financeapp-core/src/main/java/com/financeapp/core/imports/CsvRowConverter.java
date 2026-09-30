package com.financeapp.core.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Applique une correspondance de colonnes aux lignes d'un CSV. Chaque ligne invalide est signalee, jamais ignoree. */
public final class CsvRowConverter {

    public List<ImportedRow> convert(CsvTable table, CsvMapping m) {
        List<ImportedRow> result = new ArrayList<>();
        for (int i = m.headerRows(); i < table.rows().size(); i++) {
            int line = i + 1;
            String label = table.cell(i, m.labelColumn()).strip();
            if (m.detailColumn() != null && !table.cell(i, m.detailColumn()).isBlank()) {
                label = (label + " " + table.cell(i, m.detailColumn()).strip()).strip();
            }
            String dateText = table.cell(i, m.dateColumn());
            Optional<LocalDate> date = CsvMappingGuesser.parseDate(dateText, m.datePattern());
            if (date.isEmpty()) {
                result.add(ImportedRow.invalid(line, label, "Date illisible : « " + dateText + " »"));
                continue;
            }
            Optional<BigDecimal> amount;
            String error = null;
            if (m.mode() == CsvMapping.AmountMode.SIGNED) {
                String text = table.cell(i, m.amountColumn() == null ? -1 : m.amountColumn());
                amount = AmountText.parse(text);
                if (amount.isEmpty()) {
                    error = "Montant illisible : « " + text + " »";
                }
            } else {
                Optional<BigDecimal> debit = AmountText.parse(table.cell(i, m.debitColumn())).filter(v -> v.signum() != 0);
                Optional<BigDecimal> credit = AmountText.parse(table.cell(i, m.creditColumn())).filter(v -> v.signum() != 0);
                if (debit.isPresent() && credit.isPresent()) {
                    amount = Optional.empty();
                    error = "Débit et crédit renseignés sur la même ligne";
                } else if (debit.isPresent()) {
                    amount = debit.map(v -> v.abs().negate());
                } else if (credit.isPresent()) {
                    amount = credit.map(BigDecimal::abs);
                } else {
                    amount = Optional.empty();
                    error = "Ni débit ni crédit";
                }
            }
            if (error == null && amount.get().signum() == 0) {
                error = "Montant nul";
            }
            if (error == null && label.isBlank()) {
                error = "Libellé vide";
            }
            result.add(error != null ? ImportedRow.invalid(line, label, error)
                    : new ImportedRow(line, date.get(), label, amount.get(), null, null));
        }
        return result;
    }
}
