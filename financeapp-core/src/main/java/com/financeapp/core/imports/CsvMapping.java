package com.financeapp.core.imports;

/**
 * Correspondance entre les colonnes d'un CSV et les champs d'une operation.
 * Les index de colonnes commencent a 0 ; {@code null} = colonne absente.
 *
 * @param headerRows     nombre de lignes d'en-tete a ignorer
 * @param datePattern    format de date ({@link java.time.format.DateTimeFormatter}), ex. "dd/MM/yyyy"
 * @param detailColumn   colonne complementaire ajoutee au libelle (ex. "Libellé complémentaire")
 * @param amountColumn   montant signe (mode {@link AmountMode#SIGNED})
 * @param debitColumn    sorties (mode {@link AmountMode#DEBIT_CREDIT}) ; le signe ecrit est ignore
 * @param creditColumn   entrees (mode {@link AmountMode#DEBIT_CREDIT})
 */
public record CsvMapping(
        int headerRows,
        int dateColumn,
        String datePattern,
        int labelColumn,
        Integer detailColumn,
        AmountMode mode,
        Integer amountColumn,
        Integer debitColumn,
        Integer creditColumn) {

    public enum AmountMode {
        SIGNED("Une colonne « Montant » (signée)"),
        DEBIT_CREDIT("Deux colonnes « Débit » et « Crédit »");

        private final String label;

        AmountMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
