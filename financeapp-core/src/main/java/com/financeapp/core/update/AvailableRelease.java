package com.financeapp.core.update;

import java.time.LocalDate;

/**
 * Derniere version publiee.
 *
 * @param pageUrl page de la release (lien ouvert dans le navigateur, jamais de telechargement automatique)
 */
public record AvailableRelease(ReleaseVersion version, String pageUrl, LocalDate publishedOn) {
}
