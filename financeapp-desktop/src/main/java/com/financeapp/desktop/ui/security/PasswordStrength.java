package com.financeapp.desktop.ui.security;

/**
 * Estimation indicative de la robustesse d'un mot de passe (longueur et
 * variete). Affichee en texte, pas seulement en couleur.
 */
final class PasswordStrength {

    private PasswordStrength() {
    }

    static String label(String password) {
        return switch (score(password)) {
            case 0 -> "Trop court";
            case 1 -> "Faible";
            case 2 -> "Correct";
            case 3 -> "Solide";
            default -> "Excellent";
        };
    }

    static String styleClass(String password) {
        int score = score(password);
        return score <= 1 ? "strength-weak" : score == 2 ? "strength-fair" : "strength-good";
    }

    static int score(String p) {
        if (p == null || p.length() < com.financeapp.infra.security.VaultService.MIN_PASSWORD_LENGTH) {
            return 0;
        }
        int classes = 0;
        classes += p.chars().anyMatch(Character::isLowerCase) ? 1 : 0;
        classes += p.chars().anyMatch(Character::isUpperCase) ? 1 : 0;
        classes += p.chars().anyMatch(Character::isDigit) ? 1 : 0;
        classes += p.chars().anyMatch(c -> !Character.isLetterOrDigit(c)) ? 1 : 0;
        int score = 1;
        if (p.length() >= 14 || classes >= 3) {
            score++;
        }
        if (p.length() >= 18 || (p.length() >= 14 && classes >= 3)) {
            score++;
        }
        if (p.length() >= 24) {
            score++;
        }
        return score;
    }
}
