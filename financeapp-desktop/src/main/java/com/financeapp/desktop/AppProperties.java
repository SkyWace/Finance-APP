package com.financeapp.desktop;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration centrale de l'application ({@code application.properties}, prefixe {@code app}).
 *
 * @param name    nom affiche du produit (provisoire) : le changer ici suffit
 * @param id      nom technique du dossier de donnees ; volontairement distinct de {@code name}
 *                pour qu'un changement de nom commercial ne "perde" pas les donnees existantes
 * @param dataDir dossier de donnees force (vide = emplacement standard du systeme)
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String name, String id, String dataDir, String version) {

    public AppProperties {
        if (name == null || name.isBlank()) {
            name = "FinanceApp";
        }
        if (id == null || id.isBlank()) {
            id = "financeapp";
        }
        if (version == null || version.isBlank()) {
            version = "dev";
        }
    }
}
