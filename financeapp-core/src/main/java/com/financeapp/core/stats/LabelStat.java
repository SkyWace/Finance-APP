package com.financeapp.core.stats;

import com.financeapp.core.money.Money;

/**
 * Depenses regroupees par commercant / libelle normalise.
 *
 * @param label   libelle le plus frequent du groupe, tel qu'affiche par la banque
 * @param total   montant total depense (positif)
 * @param average depense moyenne par operation (positive)
 */
public record LabelStat(String key, String label, int count, Money total, Money average) {
}
