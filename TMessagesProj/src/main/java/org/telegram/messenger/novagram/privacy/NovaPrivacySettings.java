package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Non-sensitive persisted NovaGram privacy preferences.
 *
 * <p>PIN verifiers, encryption keys, duress state, Telegram authorization data,
 * peer identifiers, and deletion queues must never be stored here.</p>
 */
public final class NovaPrivacySettings {
    private static final String GLOBAL_PREFERENCES = "novagram_privacy_global";
    private static final String ACCOUNT_PREFERENCES_PREFIX = "novagram_privacy_account_";

    private static final String KEY_SCHEMA_VERSION = "schema_version";
    private static final String KEY_REMOTE_RETENTION_HOURS = "remote_retention_hours";
    private static final String KEY_CACHE_RETENTION_HOURS = "cache_retention_hours";
    private static final String KEY_SAVED_RETENTION_HOURS = "saved_retention_hours";
    private static final String KEY_SCREENSHOT_POLICY = "screenshot_policy";
    private static final String KEY_METADATA_POLICY = "metadata_policy";
    private static final String KEY_DOH_PROVIDER_ORDER = "doh_provider_order";
    private static final String KEY_APP_PIN_DECLINED = "app_pin_declined";
    /**
     * Night mode keys, spelled exactly as on desktop so that one line in the
     * roadmap describes both platforms.
     */
    /** Spelled the same on the desktop fork, see NovaPinLockPolicy. */
    private static final String KEY_PIN_LOCK_POLICY = "novagram_pin_lock_policy";
    private static final String KEY_NIGHT_SILENT = "novagram_night_silent";
    private static final String KEY_NIGHT_SILENT_USERS = "novagram_night_silent_users";
    private static final String KEY_NIGHT_SILENT_GROUPS = "novagram_night_silent_groups";
    private static final String KEY_NIGHT_SILENT_CHANNELS = "novagram_night_silent_channels";
    /**
     * What this client last told the Telegram server about notification
     * previews. Account scoped, because {@code show_previews} is an account
     * setting on the server side, and remembered so that the request is not
     * repeated at every start.
     */
    private static final String KEY_SERVER_PREVIEW_STATE = "server_preview_state";
    /**
     * Which account the value above was written for. The file is named after
     * the account slot, not after the account, and a slot is reused by whoever
     * signs into it next, so the value has to name its owner or it would be
     * read as an answer about a different account.
     */
    private static final String KEY_SERVER_PREVIEW_OWNER = "server_preview_owner";
    private static final String FEATURE_PREFIX = "feature_";

    private final SharedPreferences globalPreferences;
    private final SharedPreferences accountPreferences;

    public static NovaPrivacySettings global(Context context) {
        return new NovaPrivacySettings(context, null);
    }

    public static NovaPrivacySettings forAccount(Context context, int account) {
        if (account < 0) {
            throw new IllegalArgumentException("account must not be negative");
        }
        return new NovaPrivacySettings(context, account);
    }

    private NovaPrivacySettings(Context context, Integer account) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            applicationContext = context;
        }
        globalPreferences = applicationContext.getSharedPreferences(GLOBAL_PREFERENCES, Context.MODE_PRIVATE);
        accountPreferences = account == null
                ? null
                : applicationContext.getSharedPreferences(ACCOUNT_PREFERENCES_PREFIX + account, Context.MODE_PRIVATE);
        ensureCurrentSchema(globalPreferences);
        if (accountPreferences != null) {
            ensureCurrentSchema(accountPreferences);
        }
    }

    public int getSchemaVersion() {
        return readInt(globalPreferences, KEY_SCHEMA_VERSION, 0);
    }

    public boolean isFeatureEnabled(NovaPrivacyFeature feature) {
        SharedPreferences preferences = preferencesFor(feature);
        return readBoolean(preferences, FEATURE_PREFIX + feature.getStorageKey(), feature.isDefaultEnabled());
    }

    public void setFeatureEnabled(NovaPrivacyFeature feature, boolean enabled) {
        if (!feature.isUserConfigurable()) {
            throw new IllegalArgumentException(feature.name() + " is a security invariant, not a user setting");
        }
        preferencesFor(feature).edit()
                .putBoolean(FEATURE_PREFIX + feature.getStorageKey(), enabled)
                .apply();
    }

    public int getRemoteRetentionHours() {
        return getRetentionHours(requireAccountPreferences(), KEY_REMOTE_RETENTION_HOURS);
    }

    public void setRemoteRetentionHours(int hours) {
        setRetentionHours(requireAccountPreferences(), KEY_REMOTE_RETENTION_HOURS, hours);
    }

    public int getCacheRetentionHours() {
        return getRetentionHours(requireAccountPreferences(), KEY_CACHE_RETENTION_HOURS);
    }

    public void setCacheRetentionHours(int hours) {
        setRetentionHours(requireAccountPreferences(), KEY_CACHE_RETENTION_HOURS, hours);
    }

    public int getSavedMessagesRetentionHours() {
        return getRetentionHours(requireAccountPreferences(), KEY_SAVED_RETENTION_HOURS);
    }

    public void setSavedMessagesRetentionHours(int hours) {
        setRetentionHours(requireAccountPreferences(), KEY_SAVED_RETENTION_HOURS, hours);
    }

    public NovaPrivacyContract.ScreenshotPolicy getScreenshotPolicy() {
        String stored = readString(globalPreferences, KEY_SCREENSHOT_POLICY);
        if (stored != null) {
            try {
                return NovaPrivacyContract.ScreenshotPolicy.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // Corrupt or future values fall back to the documented safe default.
            }
        }
        return NovaPrivacyContract.DEFAULT_SCREENSHOT_POLICY;
    }

    public void setScreenshotPolicy(NovaPrivacyContract.ScreenshotPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        globalPreferences.edit().putString(KEY_SCREENSHOT_POLICY, policy.name()).apply();
    }

    public NovaPrivacyContract.MetadataSanitizationPolicy getMetadataPolicy() {
        String stored = readString(globalPreferences, KEY_METADATA_POLICY);
        if (stored != null) {
            try {
                return NovaPrivacyContract.MetadataSanitizationPolicy.valueOf(stored);
            } catch (IllegalArgumentException ignored) {
                // Corrupt or future values fall back to the documented safe default.
            }
        }
        return NovaPrivacyContract.DEFAULT_METADATA_POLICY;
    }

    public void setMetadataPolicy(NovaPrivacyContract.MetadataSanitizationPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        globalPreferences.edit().putString(KEY_METADATA_POLICY, policy.name()).apply();
    }

    public List<NovaPrivacyContract.DohProvider> getDohProviderOrder() {
        String stored = readString(globalPreferences, KEY_DOH_PROVIDER_ORDER);
        if (stored == null || stored.length() == 0) {
            return new ArrayList<>(NovaPrivacyContract.DEFAULT_DOH_ORDER);
        }
        List<NovaPrivacyContract.DohProvider> providers = new ArrayList<>();
        Set<NovaPrivacyContract.DohProvider> seen = new HashSet<>();
        String[] ids = stored.split(",");
        for (String id : ids) {
            NovaPrivacyContract.DohProvider provider = NovaPrivacyContract.DohProvider.fromId(id);
            if (provider != null && seen.add(provider)) {
                providers.add(provider);
            }
        }
        return providers.isEmpty()
                ? new ArrayList<>(NovaPrivacyContract.DEFAULT_DOH_ORDER)
                : providers;
    }

    public void setDohProviderOrder(List<NovaPrivacyContract.DohProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("At least one encrypted DNS provider is required");
        }
        StringBuilder encoded = new StringBuilder();
        Set<NovaPrivacyContract.DohProvider> seen = new HashSet<>();
        for (NovaPrivacyContract.DohProvider provider : providers) {
            if (provider == null || !seen.add(provider)) {
                throw new IllegalArgumentException("DNS provider order contains null or duplicate values");
            }
            if (encoded.length() > 0) {
                encoded.append(',');
            }
            encoded.append(provider.getId());
        }
        globalPreferences.edit().putString(KEY_DOH_PROVIDER_ORDER, encoded.toString()).apply();
    }

    /**
     * Whether the user chose to continue without an application PIN when it was
     * offered after signing in. Non-sensitive: it only suppresses the PIN
     * prompt, it is not a verifier. Enrolling a PIN later clears it, which
     * re-enables the gate and the emergency PIN.
     */
    public boolean isAppPinDeclined() {
        return readBoolean(globalPreferences, KEY_APP_PIN_DECLINED, false);
    }

    public void setAppPinDeclined(boolean declined) {
        globalPreferences.edit().putBoolean(KEY_APP_PIN_DECLINED, declined).apply();
    }

    /**
     * Night mode is on by default, and an explicit "off" outlives a change of
     * that default. Shared preferences give the three states this needs for
     * free: a key that was never written falls back to the current default,
     * while a stored {@code false} stays false. Nothing may seed the default —
     * writing it on first run would turn "untouched" into "explicitly on" and
     * quietly break the promise.
     */
    /**
     * When the application PIN is asked for again. Stored by key rather than
     * by ordinal, and an unreadable value falls back to the strictest option
     * rather than to none: a damaged preference must not quietly weaken a
     * protection the user chose.
     */
    public NovaPinLockPolicy getPinLockPolicy() {
        return NovaPinLockPolicy.fromStorageKey(
                readString(globalPreferences, KEY_PIN_LOCK_POLICY),
                NovaPinLockPolicy.getDefault());
    }

    public void setPinLockPolicy(NovaPinLockPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        globalPreferences.edit()
                .putString(KEY_PIN_LOCK_POLICY, policy.getStorageKey())
                .apply();
    }

    public boolean isNightSilentEnabled() {
        return readBoolean(globalPreferences, KEY_NIGHT_SILENT, true);
    }

    public void setNightSilentEnabled(boolean enabled) {
        globalPreferences.edit().putBoolean(KEY_NIGHT_SILENT, enabled).apply();
    }

    public boolean isNightSilentForUsers() {
        return readBoolean(globalPreferences, KEY_NIGHT_SILENT_USERS, true);
    }

    public void setNightSilentForUsers(boolean enabled) {
        globalPreferences.edit().putBoolean(KEY_NIGHT_SILENT_USERS, enabled).apply();
    }

    public boolean isNightSilentForGroups() {
        return readBoolean(globalPreferences, KEY_NIGHT_SILENT_GROUPS, true);
    }

    public void setNightSilentForGroups(boolean enabled) {
        globalPreferences.edit().putBoolean(KEY_NIGHT_SILENT_GROUPS, enabled).apply();
    }

    public boolean isNightSilentForChannels() {
        return readBoolean(globalPreferences, KEY_NIGHT_SILENT_CHANNELS, true);
    }

    public void setNightSilentForChannels(boolean enabled) {
        globalPreferences.edit().putBoolean(KEY_NIGHT_SILENT_CHANNELS, enabled).apply();
    }

    /**
     * The values are the {@code SERVER_PREVIEW_*} constants of
     * {@link NovaNotificationPrivacy}. Zero means the server has never been
     * told anything by this client, which is also what a fresh install
     * answers — so the very first start still sends the request. A value left
     * by an account that used to sit in this slot reads as zero too.
     *
     * @param ownerId the identifier of the signed-in user, from
     *                {@code UserConfig.getClientUserId()}.
     */
    public int getServerPreviewState(long ownerId) {
        SharedPreferences preferences = requireAccountPreferences();
        if (ownerId == 0 || readLong(preferences, KEY_SERVER_PREVIEW_OWNER, 0L) != ownerId) {
            return 0;
        }
        return readInt(preferences, KEY_SERVER_PREVIEW_STATE, 0);
    }

    public void setServerPreviewState(int state, long ownerId) {
        requireAccountPreferences().edit()
                .putInt(KEY_SERVER_PREVIEW_STATE, state)
                .putLong(KEY_SERVER_PREVIEW_OWNER, ownerId)
                .apply();
    }

    private SharedPreferences preferencesFor(NovaPrivacyFeature feature) {
        if (feature == null) {
            throw new IllegalArgumentException("feature must not be null");
        }
        if (feature.getScope() == NovaPrivacyFeature.Scope.GLOBAL) {
            return globalPreferences;
        }
        return requireAccountPreferences();
    }

    private SharedPreferences requireAccountPreferences() {
        if (accountPreferences == null) {
            throw new IllegalStateException("This setting requires an account-scoped NovaPrivacySettings instance");
        }
        return accountPreferences;
    }

    private static int getRetentionHours(SharedPreferences preferences, String key) {
        int stored = readInt(preferences, key, NovaPrivacyContract.DEFAULT_RETENTION_HOURS);
        return NovaPrivacyContract.isValidRetentionHours(stored)
                ? stored
                : NovaPrivacyContract.DEFAULT_RETENTION_HOURS;
    }

    private static void setRetentionHours(SharedPreferences preferences, String key, int hours) {
        preferences.edit()
                .putInt(key, NovaPrivacyContract.requireRetentionHours(hours))
                .apply();
    }

    private static void ensureCurrentSchema(SharedPreferences preferences) {
        int version = readInt(preferences, KEY_SCHEMA_VERSION, 0);
        if (version <= 0) {
            preferences.edit().putInt(KEY_SCHEMA_VERSION, NovaPrivacyContract.SCHEMA_VERSION).apply();
        }
    }

    private static boolean readBoolean(SharedPreferences preferences, String key, boolean fallback) {
        try {
            return preferences.getBoolean(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }

    private static int readInt(SharedPreferences preferences, String key, int fallback) {
        try {
            return preferences.getInt(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }

    private static long readLong(SharedPreferences preferences, String key, long fallback) {
        try {
            return preferences.getLong(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }

    private static String readString(SharedPreferences preferences, String key) {
        try {
            return preferences.getString(key, null);
        } catch (ClassCastException ignored) {
            return null;
        }
    }
}
