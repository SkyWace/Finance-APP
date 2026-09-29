package com.financeapp.infra.security;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Derivation d'une cle de 256 bits a partir d'un secret, via Argon2id (Bouncy Castle). */
public final class KeyDerivation {

    public static final int KEY_BYTES = 32;

    private KeyDerivation() {
    }

    public static byte[] argon2id(char[] secret, byte[] salt, Argon2Params params) {
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB())
                .withIterations(params.iterations())
                .withParallelism(params.parallelism())
                .build());
        byte[] key = new byte[KEY_BYTES];
        generator.generateBytes(secret, key);
        return key;
    }
}
