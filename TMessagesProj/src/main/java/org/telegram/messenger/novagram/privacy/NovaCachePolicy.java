package org.telegram.messenger.novagram.privacy;

/** Android-independent validation and expiry rules for encrypted cache blobs. */
public final class NovaCachePolicy {
    public static final long HOUR_MILLIS = 60L * 60L * 1000L;
    public static final int BLOB_ID_HEX_LENGTH = 32;

    private NovaCachePolicy() {
    }

    public static long expirationFor(long createdEpochMillis, int retentionHours) {
        if (createdEpochMillis <= 0L) {
            throw new IllegalArgumentException("createdEpochMillis must be positive");
        }
        NovaPrivacyContract.requireRetentionHours(retentionHours);
        long duration = retentionHours * HOUR_MILLIS;
        if (createdEpochMillis > Long.MAX_VALUE - duration) {
            throw new IllegalArgumentException("cache expiration overflows");
        }
        return createdEpochMillis + duration;
    }

    public static boolean isExpired(
            long createdEpochMillis,
            long expiresEpochMillis,
            long nowEpochMillis
    ) {
        if (createdEpochMillis <= 0L || expiresEpochMillis <= createdEpochMillis) {
            return true;
        }
        long retention = expiresEpochMillis - createdEpochMillis;
        long minimum = NovaPrivacyContract.MIN_RETENTION_HOURS * HOUR_MILLIS;
        long maximum = NovaPrivacyContract.MAX_RETENTION_HOURS * HOUR_MILLIS;
        if (retention < minimum || retention > maximum) {
            return true;
        }
        // A wall-clock rollback must never extend retention indefinitely.
        return nowEpochMillis < createdEpochMillis || nowEpochMillis >= expiresEpochMillis;
    }

    public static boolean isValidBlobId(String blobId) {
        if (blobId == null || blobId.length() != BLOB_ID_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < blobId.length(); i++) {
            char value = blobId.charAt(i);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
