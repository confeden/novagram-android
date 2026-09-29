package org.telegram.messenger.novagram.privacy;

import android.os.Build;
import android.security.keystore.KeyPermanentlyInvalidatedException;

/**
 * Tells a Keystore failure that will pass from one that will not.
 *
 * <p>The two look alike from the calling side. A key used on a Keystore that is
 * busy - right after boot, while the screen lock changes, after the system
 * unfreezes a cached process - fails with the same {@code InvalidKeyException}
 * as a key the secure hardware will never accept again. The second is what a
 * system update leaves behind when it cannot carry a key blob over to the new
 * version or boot state: the Keystore still lists the entry, and every use of
 * it answers "Keystore operation failed" for good. Only the reinstall that
 * clears the application's Keystore namespace ended that.</p>
 *
 * <p>Final is claimed only where the platform says so in words: the key was
 * invalidated, or, from API 33, a Keystore error that is not transient, not a
 * system error and names the key itself. Everything else stays "could not
 * ask". Getting this wrong in the final direction costs no data - nothing here
 * deletes anything, it only decides what the screen says and offers.</p>
 */
public final class NovaKeystoreFailure {
    private static final int MAX_CHAIN = 8;
    private static final int MAX_DETAIL = 200;

    private NovaKeystoreFailure() {
    }

    /** True when the Keystore said this key is never going to work again. */
    public static boolean isFinal(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CHAIN; t = t.getCause(), depth++) {
            if (t instanceof KeyPermanentlyInvalidatedException) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= 33 && t instanceof android.security.KeyStoreException) {
                return isFinalKeystoreError((android.security.KeyStoreException) t);
            }
        }
        return false;
    }

    private static boolean isFinalKeystoreError(android.security.KeyStoreException e) {
        if (Build.VERSION.SDK_INT < 33) {
            return false;
        }
        if (e.isTransientFailure() || e.isSystemError() || e.requiresUserAuthentication()
                || e.getRetryPolicy() != android.security.KeyStoreException.RETRY_NEVER) {
            return false;
        }
        int code = e.getNumericErrorCode();
        // ERROR_KEYMINT_FAILURE is where every KeyMint error without a public
        // name lands - the invalid key blob a failed upgrade leaves among them.
        // The busy and communication failures of the secure hardware are
        // marked transient and never get here.
        return code == android.security.KeyStoreException.ERROR_KEY_CORRUPTED
                || code == android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST
                || code == android.security.KeyStoreException.ERROR_KEYMINT_FAILURE;
    }

    /**
     * One line naming the failure, for the owner to pass on. Class names along
     * the cause chain, the public Keystore code where there is one, and the
     * innermost message, which on keystore2 carries the internal KeyMint code.
     * Holds no key material: none of these messages ever does.
     */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        Throwable innermost = failure;
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CHAIN; t = t.getCause(), depth++) {
            if (line.length() > 0) {
                line.append(" ← ");
            }
            line.append(t.getClass().getSimpleName());
            if (Build.VERSION.SDK_INT >= 33 && t instanceof android.security.KeyStoreException) {
                line.append(" #").append(((android.security.KeyStoreException) t).getNumericErrorCode());
            }
            innermost = t;
        }
        String message = innermost.getMessage();
        if (message != null && !message.isEmpty()) {
            line.append(": ").append(message.replace('\n', ' ').trim());
        }
        if (line.length() > MAX_DETAIL) {
            line.setLength(MAX_DETAIL - 1);
            line.append('…');
        }
        return line.toString();
    }
}
