package com.financeapp.core.imports;

import java.util.List;

/**
 * Contenu brut d'un fichier CSV.
 *
 * @param rows toutes les lignes non vides, en-tete eventuelle comprise (la correspondance dit s'il y en a une)
 */
public record CsvTable(List<List<String>> rows, char delimiter, String charset) {

    public int columnCount() {
        return rows.stream().mapToInt(List::size).max().orElse(0);
    }

    public String cell(int row, int column) {
        List<String> r = rows.get(row);
        return column < r.size() ? r.get(column) : "";
    }
}
