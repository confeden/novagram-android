package org.telegram.messenger.novagram.privacy;

import java.util.Arrays;

/** Best-effort mutable secret clearing with a volatile read-after-write fence. */
public final class NovaSecretWiper {
    private static volatile int wipeFence;

    private NovaSecretWiper() {
    }

    public static void wipe(char[] value) {
        if (value == null) {
            return;
        }
        Arrays.fill(value, '\0');
        if (value.length > 0) {
            wipeFence ^= value[value.length - 1];
        }
    }

    public static void wipe(byte[] value) {
        if (value == null) {
            return;
        }
        Arrays.fill(value, (byte) 0);
        if (value.length > 0) {
            wipeFence ^= value[value.length - 1];
        }
    }
}
