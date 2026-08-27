package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;

/**
 * Whether a channel is allowed to decide, on the user's behalf, that its posts
 * are to be sent to Telegram for translation.
 *
 * <p>Upstream reads {@code Chat.autotranslation} — a flag the channel owner
 * sets — and uses it as the default answer to "is this dialog being
 * translated". Nothing is pressed and nothing is asked: opening the channel is
 * enough for the post text to leave the device inside
 * {@code messages.translateText}. The fork stops reading that flag by default.
 * </p>
 *
 * <p>This only removes the <em>default</em>. A dialog the user switched on by
 * hand carries an explicit {@code true} in {@code translatingDialogs}, and that
 * entry is read before this policy is ever consulted, so a translation somebody
 * asked for keeps working.</p>
 */
public final class NovaTranslationPolicy {
    private NovaTranslationPolicy() {
    }

    /**
     * True when the channel's own flag must be ignored. No context means no
     * answer from storage, and the safe answer is the one that keeps the text
     * on the device.
     */
    public static boolean isChannelFlagIgnored() {
        Context context = ApplicationLoader.applicationContext;
        return context == null
                || NovaPrivacySettings.global(context)
                .isFeatureEnabled(NovaPrivacyFeature.AUTOTRANSLATE_BLOCKED);
    }

    public static void setChannelFlagIgnored(Context context, boolean ignored) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context != null) {
            NovaPrivacySettings.global(context)
                    .setFeatureEnabled(NovaPrivacyFeature.AUTOTRANSLATE_BLOCKED, ignored);
        }
    }
}
