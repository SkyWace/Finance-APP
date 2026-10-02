package com.financeapp.core.port;

import com.financeapp.core.update.AvailableRelease;

import java.io.IOException;
import java.util.Optional;

/** Source de la derniere version publiee (requete reseau sortante, sans aucune donnee de l'utilisateur). */
public interface ReleaseFeed {

    Optional<AvailableRelease> latest() throws IOException;
}
