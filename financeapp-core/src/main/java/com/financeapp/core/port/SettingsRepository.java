package com.financeapp.core.port;

import java.util.Optional;

/** Stockage cle/valeur des parametres utilisateur. */
public interface SettingsRepository {

    Optional<String> get(String key);

    void put(String key, String value);
}
