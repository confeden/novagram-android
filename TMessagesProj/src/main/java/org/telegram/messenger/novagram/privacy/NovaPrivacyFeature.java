package org.telegram.messenger.novagram.privacy;

/**
 * Stable identifiers for NovaGram privacy features.
 *
 * <p>Preference keys are explicit and must not be derived from enum names. Enum
 * names may change during an upstream integration; stored keys may not.</p>
 */
public enum NovaPrivacyFeature {
    FIRST_RUN_PIN("first_run_pin", Scope.GLOBAL, true, false),
    DURESS_MODE("duress_mode", Scope.GLOBAL, true, true),
    SECRET_CHAT_FIRST("secret_chat_first", Scope.ACCOUNT, true, true),
    ADVANCED_MESSAGE_RETENTION("advanced_message_retention", Scope.ACCOUNT, true, true),
    ADMIN_CHAT_RETENTION_EXEMPTIONS("admin_chat_retention_exemptions", Scope.ACCOUNT, true, true),
    ENCRYPTED_CACHE("encrypted_cache", Scope.GLOBAL, true, false),
    LOCAL_CACHE_RETENTION("local_cache_retention", Scope.ACCOUNT, true, true),
    SAVED_MESSAGES_RETENTION("saved_messages_retention", Scope.ACCOUNT, true, true),
    SCREENSHOT_PROTECTION("screenshot_protection", Scope.GLOBAL, true, true),
    METADATA_SANITIZATION("metadata_sanitization", Scope.GLOBAL, true, true),
    READ_STATUS_HIDING("read_status_hiding", Scope.ACCOUNT, true, true),
    NOTIFICATION_TEXT_OFF_SERVERS("notification_text_off_servers", Scope.ACCOUNT, true, true),
    SEARCH_HISTORY_PROTECTION("search_history_protection", Scope.ACCOUNT, true, true),
    SHOW_PEER_IDS("show_peer_ids", Scope.ACCOUNT, true, true),
    ERASE_EVIDENCE("erase_evidence", Scope.ACCOUNT, true, true),
    STRICT_ENCRYPTED_DNS("strict_encrypted_dns", Scope.GLOBAL, true, false),
    FORCED_CLOUDFLARE_TRANSPORT("forced_cloudflare_transport", Scope.GLOBAL, true, false),
    PROTECTED_CALLS("protected_calls", Scope.GLOBAL, false, false);

    public enum Scope {
        GLOBAL,
        ACCOUNT
    }

    private final String storageKey;
    private final Scope scope;
    private final boolean defaultEnabled;
    private final boolean userConfigurable;

    NovaPrivacyFeature(String storageKey, Scope scope, boolean defaultEnabled, boolean userConfigurable) {
        this.storageKey = storageKey;
        this.scope = scope;
        this.defaultEnabled = defaultEnabled;
        this.userConfigurable = userConfigurable;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Scope getScope() {
        return scope;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    public boolean isUserConfigurable() {
        return userConfigurable;
    }
}
