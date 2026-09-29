package com.financeapp.infra.security;

/**
 * Parametres Argon2id. Les valeurs lues dans un trousseau sont bornees : un
 * fichier forge ne doit pas pouvoir reclamer des gigaoctets de memoire.
 *
 * @param memoryKiB   memoire en Kio
 * @param iterations  nombre de passes
 * @param parallelism nombre de voies
 */
public record Argon2Params(int memoryKiB, int iterations, int parallelism) {

    /** 64 Mio, 3 passes, 1 voie : au-dela des recommandations OWASP (19 Mio, 2 passes), ~0,3 s. */
    public static final Argon2Params DEFAULT = new Argon2Params(64 * 1024, 3, 1);

    public Argon2Params {
        if (memoryKiB < 8 || memoryKiB > 1024 * 1024) {
            throw new IllegalArgumentException("Memoire Argon2 hors bornes");
        }
        if (iterations < 1 || iterations > 20) {
            throw new IllegalArgumentException("Iterations Argon2 hors bornes");
        }
        if (parallelism < 1 || parallelism > 16) {
            throw new IllegalArgumentException("Parallelisme Argon2 hors bornes");
        }
    }
}
