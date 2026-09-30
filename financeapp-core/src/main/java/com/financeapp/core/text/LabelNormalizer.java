package com.financeapp.core.text;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Normalisation des libelles bancaires, commune a la detection d'abonnements,
 * aux regles de categorisation et a la detection de doublons :
 * minuscules, sans accents, sans chiffres ni ponctuation, sans les prefixes
 * techniques des banques ("PRLV SEPA", "CB", "PAIEMENT PAR CARTE"...).
 *
 * <p>{@code "PRLV SEPA NETFLIX.COM 12/09"} → {@code "netflix com"}.
 */
public final class LabelNormalizer {

    private static final Set<String> NOISE = Set.of(
            "prlv", "sepa", "prelevement", "cb", "carte", "paiement", "par", "facture", "vir", "virement",
            "recu", "emis", "achat", "retrait", "dab", "du", "le", "la", "les", "de", "des", "fr", "sa", "sas", "x",
            // Mois : "LOYER OCTOBRE" et "LOYER NOVEMBRE" designent le meme poste.
            "janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre",
            "novembre", "decembre");

    private LabelNormalizer() {
    }

    public static String normalize(String label) {
        if (label == null) {
            return "";
        }
        String s = Normalizer.normalize(label.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z ]", " ");
        return String.join(" ", Arrays.stream(s.split("\\s+"))
                .filter(w -> !w.isBlank() && !NOISE.contains(w))
                .toList());
    }

    /**
     * Mot-cle representatif d'un libelle, propose comme motif de regle :
     * premier mot significatif ({@code "CB CARREFOUR MARKET 1234"} → {@code "carrefour"}).
     */
    public static String keyword(String label) {
        String n = normalize(label);
        return Arrays.stream(n.split(" ")).filter(w -> w.length() >= 3).findFirst()
                .orElse(n.isBlank() ? "" : n.split(" ")[0]);
    }

    /**
     * Vrai si le motif (normalise) apparait dans le libelle (normalise) sous forme de
     * mots entiers consecutifs : "total" correspond a "station total access" mais
     * pas a "totalement".
     */
    public static boolean containsWords(String normalizedLabel, String normalizedPattern) {
        if (normalizedPattern.isBlank()) {
            return false;
        }
        return (" " + normalizedLabel + " ").contains(" " + normalizedPattern + " ");
    }
}
