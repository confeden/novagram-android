package org.telegram.messenger.novagram.privacy;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NovaPinKdfTest {
    @Test
    public void verifierMatchesOnlyTheOriginalPin() throws Exception {
        byte[] salt = NovaPinKdf.newSalt(new SecureRandom());
        byte[] verifier = NovaPinKdf.createVerifier(
                new char[]{'1', '2', '3', '4'},
                salt,
                NovaPinKdf.ITERATIONS
        );
        try {
            assertTrue(NovaPinKdf.verify(
                    new char[]{'1', '2', '3', '4'},
                    salt,
                    NovaPinKdf.ITERATIONS,
                    verifier
            ));
            assertFalse(NovaPinKdf.verify(
                    new char[]{'4', '3', '2', '1'},
                    salt,
                    NovaPinKdf.ITERATIONS,
                    verifier
            ));
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(verifier, (byte) 0);
        }
    }
}
