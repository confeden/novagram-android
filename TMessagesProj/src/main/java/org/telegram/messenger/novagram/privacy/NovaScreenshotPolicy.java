package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.TLRPC;

/** Fail-closed screenshot classification for Telegram chat peers. */
public final class NovaScreenshotPolicy {
    private NovaScreenshotPolicy() {
    }

    /**
     * Whether the application's own window must carry {@code FLAG_SECURE},
     * regardless of any chat being open.
     *
     * <p>Upstream ties the whole-window flag to
     * {@code SharedConfig.passcodeHash.length() > 0}, so in the configuration
     * everyone actually runs — no passcode — the recents-screen snapshot and
     * every capture app see the chat list, the open conversation and these
     * settings. This answer does not ask about the passcode: the fork's
     * screenshot protection is a switch of its own, and it is on by default.
     * </p>
     *
     * <p>{@link NovaPrivacyContract.ScreenshotPolicy#ADAPTIVE} keeps its
     * meaning for {@link #shouldSecureChat}, which is a statement about one
     * peer. It cannot narrow the window flag: on Android every chat is drawn
     * into this same window, so "capturable in a public channel" would mean
     * "capturable in the chat list a swipe away". Only the explicit
     * {@code ALLOW_ALL} escape turns the flag off.</p>
     */
    public static boolean shouldSecureWindow(Context context) {
        if (context == null) {
            return true;
        }
        NovaPrivacySettings settings = NovaPrivacySettings.global(context);
        if (!settings.isFeatureEnabled(NovaPrivacyFeature.SCREENSHOT_PROTECTION)) {
            return false;
        }
        return settings.getScreenshotPolicy() != NovaPrivacyContract.ScreenshotPolicy.ALLOW_ALL;
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
