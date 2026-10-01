package com.financeapp.infra.storage;

/**
 * Utilisateur de l'application sur cet ordinateur. Chaque profil a son propre
 * dossier : base chiffree, trousseau (mot de passe maitre et cle de
 * recuperation), sauvegardes et journaux. Un profil ne peut pas lire les
 * donnees d'un autre.
 *
 * @param id          identifiant technique stable
 * @param name        nom affiche (seule information visible avant deverrouillage)
 * @param directories dossiers propres au profil
 */
public record Profile(String id, String name, AppDirectories directories) {
}
