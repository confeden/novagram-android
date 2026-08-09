package org.telegram.messenger.novagram.privacy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** PIN verifier derivation without creating immutable PIN strings. */
public final class NovaPinKdf {
    public static final int ITERATIONS = 600_000;
    public static final int SALT_BYTES = 32;
    public static final int VERIFIER_BYTES = 32;

    private static final int DERIVED_KEY_BITS = 256;
    private static final byte[] VERIFIER_DOMAIN =
            "NovaGram PIN verifier v1".getBytes(StandardCharsets.US_ASCII);

    private NovaPinKdf() {
    }

    public static byte[] newSalt(SecureRandom random) {
        if (random == null) {
            throw new IllegalArgumentException("random must not be null");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return salt;
    }

    public static byte[] createVerifier(char[] pin, byte[] salt, int iterations)
            throws GeneralSecurityException {
        if (!NovaPrivacyContract.isValidPin(pin)) {
            throw new IllegalArgumentException("PIN must contain four to six ASCII digits");
        }
        if (salt == null || salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("salt must contain " + SALT_BYTES + " bytes");
        }
        if (iterations < ITERATIONS) {
            throw new IllegalArgumentException("KDF iteration count is below the security floor");
        }

        PBEKeySpec spec = new PBEKeySpec(pin, salt, iterations, DERIVED_KEY_BITS);
        byte[] derived = null;
        try {
            derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            byte[] verifier = mac.doFinal(VERIFIER_DOMAIN);
            if (verifier.length != VERIFIER_BYTES) {
                NovaSecretWiper.wipe(verifier);
                throw new GeneralSecurityException("Unexpected HMAC output length");
            }
            return verifier;
        } finally {
            spec.clearPassword();
            if (derived != null) {
                NovaSecretWiper.wipe(derived);
            }
        }
    }

    public static boolean verify(char[] pin, byte[] salt, int iterations, byte[] expected)
            throws GeneralSecurityException {
        if (expected == null || expected.length != VERIFIER_BYTES) {
            throw new IllegalArgumentException("expected verifier has an invalid length");
        }
        byte[] actual = createVerifier(pin, salt, iterations);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            NovaSecretWiper.wipe(actual);
        }
    }
}
