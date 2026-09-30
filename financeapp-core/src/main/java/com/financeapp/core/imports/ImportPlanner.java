package com.financeapp.core.imports;

import com.financeapp.core.categorization.CategorizationEngine;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.text.LabelNormalizer;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Analyse chaque ligne a importer par rapport aux donnees existantes du compte.
 * Principe : <b>rien d'ambigu n'est importe en silence</b>.
 *
 * <ol>
 *   <li>Identifiant bancaire (OFX) deja connu → doublon certain.</li>
 *   <li>Operation existante de meme montant a ±{@value #DUPLICATE_DAYS} jours : libelles
 *       equivalents → doublon ; libelles differents → doublon possible (ex. saisie manuelle
 *       "Carrefour" contre "CB CARREFOUR MARKET 1234").</li>
 *   <li>Ligne identique deja vue plus haut dans le fichier → a confirmer.</li>
 *   <li>Operation prevue de meme montant a ±{@value #MATCH_DAYS} jours → la ligne la realise
 *       (l'operation prevue devient effectuee, rien n'est cree en double).</li>
 *   <li>Echeance recurrente non traitee, montant a ±{@value #RECURRING_TOLERANCE_PERCENT} %
 *       (factures variables) et ±{@value #MATCH_DAYS} jours → la ligne importee est rattachee
 *       a l'echeance, qui cesse d'etre "a venir".</li>
 * </ol>
 * Sans ces rapprochements, un loyer importe serait compte deux fois dans le
 * disponible reel : une fois dans le solde, une fois comme depense a venir.
 * Chaque operation existante ou prevue ne peut etre associee qu'a une seule ligne.
 */
public final class ImportPlanner {

    static final int DUPLICATE_DAYS = 3;
    static final int MATCH_DAYS = 5;
    /** Fenetre elargie quand le libelle confirme le rapprochement (date prevue approximative). */
    static final int MATCH_DAYS_SAME_LABEL = 10;
    static final int RECURRING_TOLERANCE_PERCENT = 10;

    /**
     * @param existing    operations deja enregistrees sur le compte autour des dates du fichier
     *                    (seules les effectuees et en attente peuvent etre des doublons ; les prevues
     *                    sont rapprochees via {@code pending})
     * @param externalIds identifiants bancaires deja importes sur ce compte
     * @param pending     operations a venir du compte (prevues et occurrences recurrentes), retards compris
     */
    public List<ImportCandidate> plan(List<ImportedRow> rows, long accountId, Currency currency,
                                      List<Transaction> existing, Set<String> externalIds,
                                      List<PlannedItem> pending, CategorizationEngine categorization) {
        List<Transaction> available = new ArrayList<>(existing.stream()
                .filter(t -> t.accountId() == accountId && t.status().countsInBalance()).toList());
        List<PlannedItem> openItems = new ArrayList<>(pending.stream()
                .filter(p -> p.accountId() == accountId && p.type() != TransactionType.TRANSFER).toList());
        Set<String> seenInFile = new HashSet<>();
        Set<String> idsInFile = new HashSet<>();
        List<ImportCandidate> result = new ArrayList<>();

        for (ImportedRow row : rows) {
            if (!row.isValid()) {
                result.add(new ImportCandidate(row, ImportCandidate.Kind.INVALID, null, null, null, false));
                continue;
            }
            Money amount = Money.of(row.amount(), currency);
            TransactionType type = amount.isNegative() ? TransactionType.EXPENSE : TransactionType.INCOME;
            var suggestion = categorization.suggest(row.label(), type).orElse(null);
            String normalized = LabelNormalizer.normalize(row.label());

            if (row.externalId() != null && (externalIds.contains(row.externalId()) || !idsInFile.add(row.externalId()))) {
                result.add(new ImportCandidate(row, ImportCandidate.Kind.DUPLICATE, null, null, suggestion, false));
                continue;
            }

            Optional<Transaction> sameAmount = available.stream()
                    .filter(t -> t.amount().equals(amount) && days(t.date(), row.date()) <= DUPLICATE_DAYS)
                    .min(Comparator.comparingLong(t -> days(t.date(), row.date())));
            if (sameAmount.isPresent()) {
                Transaction match = sameAmount.get();
                available.remove(match);
                boolean sameLabel = similar(normalized, LabelNormalizer.normalize(match.label()));
                result.add(new ImportCandidate(row, sameLabel ? ImportCandidate.Kind.DUPLICATE
                        : ImportCandidate.Kind.POSSIBLE_DUPLICATE, match, null, suggestion, false));
                continue;
            }

            String fileKey = row.date() + "|" + row.amount().stripTrailingZeros() + "|" + normalized;
            if (!seenInFile.add(fileKey)) {
                result.add(new ImportCandidate(row, ImportCandidate.Kind.DUPLICATE_IN_FILE, null, null, suggestion, false));
                continue;
            }

            Optional<PlannedItem> planned = openItems.stream()
                    .filter(p -> p.source() == PlannedItem.Source.PLANNED_TRANSACTION && p.amount().equals(amount)
                            && closeEnough(p, row, normalized))
                    .min(Comparator.comparingLong(p -> days(p.date(), row.date())));
            if (planned.isEmpty()) {
                planned = openItems.stream()
                        .filter(p -> p.source() == PlannedItem.Source.RECURRING && p.type() == type
                                && withinTolerance(p.amount(), amount) && closeEnough(p, row, normalized))
                        .min(Comparator.comparingLong((PlannedItem p) -> days(p.date(), row.date()))
                                .thenComparing(p -> p.amount().minus(amount).abs().amount()));
            }
            if (planned.isPresent()) {
                PlannedItem p = planned.get();
                openItems.remove(p);
                ImportCandidate.Kind kind = p.source() == PlannedItem.Source.RECURRING
                        ? ImportCandidate.Kind.MATCHES_RECURRING : ImportCandidate.Kind.MATCHES_PLANNED;
                // La categorie de l'operation prevue prime sur la suggestion.
                var s = p.categoryId() != null
                        ? new com.financeapp.core.categorization.CategorySuggestion(p.categoryId(),
                        com.financeapp.core.categorization.CategorySuggestion.Source.PLANNED, null)
                        : suggestion;
                result.add(new ImportCandidate(row, kind, null, p, s, true));
                continue;
            }
            result.add(new ImportCandidate(row, ImportCandidate.Kind.NEW, null, null, suggestion, true));
        }
        return result;
    }

    private static boolean closeEnough(PlannedItem p, ImportedRow row, String normalizedLabel) {
        long d = days(p.date(), row.date());
        return d <= MATCH_DAYS
                || d <= MATCH_DAYS_SAME_LABEL && similar(normalizedLabel, LabelNormalizer.normalize(p.label()));
    }

    private static long days(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    /** Libelles equivalents : egaux, ou l'un contient l'autre (libelle bancaire enrichi). */
    static boolean similar(String a, String b) {
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        return a.equals(b) || LabelNormalizer.containsWords(a, b) || LabelNormalizer.containsWords(b, a);
    }

    private static boolean withinTolerance(Money expected, Money actual) {
        if (expected.signum() != actual.signum()) {
            return false;
        }
        BigDecimal diff = expected.minus(actual).abs().amount();
        BigDecimal limit = expected.abs().amount().multiply(BigDecimal.valueOf(RECURRING_TOLERANCE_PERCENT))
                .divide(BigDecimal.valueOf(100));
        return diff.compareTo(limit) <= 0;
    }
}
