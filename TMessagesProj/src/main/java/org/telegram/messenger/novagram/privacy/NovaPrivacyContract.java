package org.telegram.messenger.novagram.privacy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Versioned, Android-independent privacy defaults and validation rules.
 *
 * <p>This class intentionally contains no Telegram integration and no storage
 * access, which keeps the contract testable across upstream beta updates.</p>
 */
public final class NovaPrivacyContract {
    public static final int SCHEMA_VERSION = 1;

    public static final int MIN_RETENTION_HOURS = 24;
    public static final int MAX_RETENTION_HOURS = 168;
    public static final int DEFAULT_RETENTION_HOURS = 168;

    public static final int MIN_PIN_LENGTH = 4;
    public static final int MAX_PIN_LENGTH = 6;
    public static final int PIN_FAILURES_BEFORE_DELAY = 3;

    public static final long FIVE_MINUTES_MILLIS = 5L * 60L * 1000L;
    public static final long MAX_PIN_DELAY_MILLIS = 24L * 60L * 60L * 1000L;

    public enum ScreenshotPolicy {
        ADAPTIVE,
        BLOCK_ALL_CHATS,
        ALLOW_ALL
    }

    public enum MetadataSanitizationPolicy {
        STRICT,
        STANDARD,
        DISABLED
    }

    public enum DohProvider {
        CLOUDFLARE("cloudflare", "https://cloudflare-dns.com/dns-query"),
        GOOGLE("google", "https://dns.google/dns-query"),
        ADGUARD("adguard", "https://dns.adguard-dns.com/dns-query"),
        QUAD9("quad9", "https://dns.quad9.net/dns-query");

        private final String id;
        private final String endpoint;

        DohProvider(String id, String endpoint) {
            this.id = id;
            this.endpoint = endpoint;
        }

        public String getId() {
            return id;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public static DohProvider fromId(String id) {
            if (id == null) {
                return null;
            }
            for (DohProvider provider : values()) {
                if (provider.id.equals(id)) {
                    return provider;
                }
            }
            return null;
        }
    }

    public static final ScreenshotPolicy DEFAULT_SCREENSHOT_POLICY = ScreenshotPolicy.ADAPTIVE;
    public static final MetadataSanitizationPolicy DEFAULT_METADATA_POLICY = MetadataSanitizationPolicy.STRICT;

    /**
     * The order the encrypted DNS endpoints are tried in. Deliberately not a
     * default and not a setting: the promise is that this list cannot be
     * reassigned by the user, by settings, by the system or by the network, and
     * a stored order would be exactly such a reassignment. It was one until
     * 2026-08-15, when {@code doh_provider_order} was removed from preferences.
     * The same four endpoints in the same order are compiled into the desktop
     * client; if one side changes, both change.
     */
    public static final List<DohProvider> DOH_ORDER = Collections.unmodifiableList(Arrays.asList(
            DohProvider.CLOUDFLARE,
            DohProvider.GOOGLE,
            DohProvider.ADGUARD,
            DohProvider.QUAD9
    ));

    private NovaPrivacyContract() {
    }

    public static int requireRetentionHours(int hours) {
        if (!isValidRetentionHours(hours)) {
            throw new IllegalArgumentException("Retention must be between "
                    + MIN_RETENTION_HOURS + " and " + MAX_RETENTION_HOURS + " hours");
        }
        return hours;
    }

    public static boolean isValidRetentionHours(int hours) {
        return hours >= MIN_RETENTION_HOURS && hours <= MAX_RETENTION_HOURS;
    }

    public static boolean isValidPin(CharSequence pin) {
        if (pin == null || pin.length() < MIN_PIN_LENGTH || pin.length() > MAX_PIN_LENGTH) {
            return false;
        }
        for (int i = 0; i < pin.length(); i++) {
            char value = pin.charAt(i);
            if (value < '0' || value > '9') {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidPin(char[] pin) {
        if (pin == null || pin.length < MIN_PIN_LENGTH || pin.length > MAX_PIN_LENGTH) {
            return false;
        }
        for (char value : pin) {
            if (value < '0' || value > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the delay started after {@code failedAttempts} consecutive failures.
     * The third failure starts five minutes; the result is capped at 24 hours.
     */
    public static long getPinRetryDelayMillis(int failedAttempts) {
        if (failedAttempts < 0) {
            throw new IllegalArgumentException("failedAttempts must not be negative");
        }
        if (failedAttempts < PIN_FAILURES_BEFORE_DELAY) {
            return 0L;
        }
        long delaySteps = failedAttempts - (PIN_FAILURES_BEFORE_DELAY - 1L);
        return Math.min(delaySteps * FIVE_MINUTES_MILLIS, MAX_PIN_DELAY_MILLIS);
    }
}
