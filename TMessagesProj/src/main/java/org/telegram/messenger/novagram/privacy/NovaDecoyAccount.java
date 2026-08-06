package org.telegram.messenger.novagram.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

/**
 * Makes the client believe it is signed in while the decoy is on.
 *
 * <p>Nothing in {@code UserConfig} is patched. The invented owner is written
 * into the very preferences the client reads at start, so
 * {@code isClientActivated()} answers yes for the ordinary reason: there is an
 * account record. From that point the whole application behaves like a normal
 * signed in Telegram, which is exactly the goal.</p>
 *
 * <p>Must run before the first {@code UserConfig.loadConfig()} of the process:
 * that call caches what it read and will not look again.</p>
 */
public final class NovaDecoyAccount {
    private static final String PREFERENCES = "userconfing";

    private NovaDecoyAccount() {
    }

    public static void ensure(Context context) {
        if (context == null || !NovaDecoyState.isActive(context)) {
            return;
        }
        Context app = context.getApplicationContext();
        Context target = app != null ? app : context;
        SharedPreferences preferences = target.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (!TextUtils.isEmpty(preferences.getString("user", null))) {
            return;
        }
        TLRPC.User self = NovaDecoyPersona.build().self;
        SerializedData data = new SerializedData();
        try {
            self.serializeToStream(data);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("user", Base64.encodeToString(data.toByteArray(), Base64.DEFAULT));
            editor.putInt("selectedAccount", 0);
            editor.putInt("loginTime", NovaDecoyState.anchor());
            // Settings the client would have fetched long ago on a real
            // account. Leaving them unset makes it ask for each of them at
            // every start, and the decoy has nobody to ask.
            editor.putBoolean("draftsLoaded", true);
            editor.putBoolean("unreadDialogsLoaded", true);
            editor.putBoolean("contactsReimported", true);
            editor.putBoolean("notificationsSettingsLoaded4", true);
            editor.putBoolean("notificationsSignUpSettingsLoaded", true);
            editor.putBoolean("filtersLoaded", true);
            // Off on purpose: contact sync writes Telegram contacts into the
            // system address book, and the invented people must not appear in
            // the real phone book of the person holding the device.
            editor.putBoolean("syncContacts", false);
            editor.putBoolean("suggestContacts", true);
            editor.putBoolean("registeredForPush", false);
            editor.commit();
        } catch (Throwable ignored) {
        } finally {
            data.cleanup();
        }
    }
}
