package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.TLRPC;

/** Fail-closed screenshot classification for Telegram chat peers. */
public final class NovaScreenshotPolicy {
    private NovaScreenshotPolicy() {
    }

    public static boolean shouldSecureChat(
            Context context,
            TLRPC.Chat chat,
            TLRPC.User user,
            TLRPC.EncryptedChat encryptedChat
    ) {
        if (context == null) {
            return true;
        }
        NovaPrivacySettings settings = NovaPrivacySettings.global(context);
        if (!settings.isFeatureEnabled(NovaPrivacyFeature.SCREENSHOT_PROTECTION)) {
            return false;
        }
        return shouldSecureChat(
                settings.getScreenshotPolicy(),
                chat,
                user,
                encryptedChat
        );
    }

    public static boolean shouldSecureChat(
            NovaPrivacyContract.ScreenshotPolicy policy,
            TLRPC.Chat chat,
            TLRPC.User user,
            TLRPC.EncryptedChat encryptedChat
    ) {
        if (policy == null) {
            policy = NovaPrivacyContract.DEFAULT_SCREENSHOT_POLICY;
        }
        switch (policy) {
            case ALLOW_ALL:
                return false;
            case BLOCK_ALL_CHATS:
                return true;
            case ADAPTIVE:
            default:
                if (encryptedChat != null || user != null) {
                    return true;
                }
                if (chat != null) {
                    // Only an active public username makes a group/channel public.
                    return !ChatObject.isPublic(chat);
                }
                // Loading, report and future peer modes are private until proven public.
                return true;
        }
    }
}
