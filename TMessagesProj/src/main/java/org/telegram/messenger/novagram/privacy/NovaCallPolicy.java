package org.telegram.messenger.novagram.privacy;

import android.content.Context;

/** Central fail-closed call gate until protected signaling and media routing exist. */
public final class NovaCallPolicy {
    private NovaCallPolicy() {
    }

    public static boolean areCallsAllowed(Context context) {
        return context != null
                && NovaPrivacySettings.global(context)
                .isFeatureEnabled(NovaPrivacyFeature.PROTECTED_CALLS);
    }
}
