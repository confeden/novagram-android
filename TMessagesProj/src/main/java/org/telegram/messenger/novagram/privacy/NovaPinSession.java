package org.telegram.messenger.novagram.privacy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.WindowManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.NovaPinGateActivity;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local PIN authorization marker and pending launch handoff. */
public final class NovaPinSession {
    private static final AtomicBoolean UNLOCKED = new AtomicBoolean(false);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable BACKGROUND_LOCK = () -> UNLOCKED.set(false);
    private static Intent pendingLaunch;

    private NovaPinSession() {
    }

    public static boolean isUnlocked() {
        if (NovaDecoyState.isActive()) {
            // The decoy has an account record of its own and would otherwise
            // trip the gate. Asking for a PIN there would announce that
            // something was destroyed on this phone, which is the one thing
            // the decoy exists to avoid.
            return true;
        }
        if (appPinDeclined()) {
            // The application PIN was offered after signing in and the user
            // chose to continue without one. The gate stays out of the way
            // until a PIN is set from NovaGram settings, which clears the flag.
            return true;
        }
        return isAccessAllowed(hasAuthenticatedAccount(), UNLOCKED.get());
    }

    private static boolean appPinDeclined() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        try {
            return NovaPrivacySettings.global(context).isAppPinDeclined();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isAccessAllowed(boolean hasAuthenticatedAccount, boolean sessionUnlocked) {
        return !hasAuthenticatedAccount || sessionUnlocked;
    }

    private static boolean hasAuthenticatedAccount() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            UserConfig config = UserConfig.getInstance(account);
            if (config.isConfigLoaded() && config.isClientActivated()) {
                return true;
            }
        }

        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            String preferencesName = account == 0 ? "userconfing" : "userconfig" + account;
            SharedPreferences preferences = context.getSharedPreferences(
                    preferencesName,
                    Context.MODE_PRIVATE
            );
            if (!TextUtils.isEmpty(preferences.getString("user", null))) {
                return true;
            }
        }
        return false;
    }

    public static void unlock() {
        UNLOCKED.set(true);
    }

    public static void lock() {
        MAIN_HANDLER.removeCallbacks(BACKGROUND_LOCK);
        UNLOCKED.set(false);
    }

    public static void scheduleBackgroundLock() {
        MAIN_HANDLER.removeCallbacks(BACKGROUND_LOCK);
        MAIN_HANDLER.postDelayed(BACKGROUND_LOCK, 500L);
    }

    public static void cancelBackgroundLock() {
        MAIN_HANDLER.removeCallbacks(BACKGROUND_LOCK);
    }

    public static synchronized void redirectToGate(Activity activity) {
        if (activity == null) {
            throw new IllegalArgumentException("activity must not be null");
        }
        pendingLaunch = new Intent(activity.getIntent());
        pendingLaunch.setClass(activity, LaunchActivity.class);
        Intent gate = new Intent(activity, NovaPinGateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(gate);
        activity.overridePendingTransition(0, 0);
    }

    /** Must be called immediately after {@code super.onCreate()} by secondary activities. */
    public static boolean guardAfterSuper(Activity activity) {
        if (isUnlocked()) {
            return false;
        }
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        redirectToGate(activity);
        activity.finish();
        return true;
    }

    public static synchronized Intent takePendingLaunch(Activity activity) {
        Intent result = pendingLaunch;
        pendingLaunch = null;
        if (result == null) {
            result = new Intent(activity, LaunchActivity.class);
        }
        result.setClass(activity, LaunchActivity.class);
        return result;
    }
}
