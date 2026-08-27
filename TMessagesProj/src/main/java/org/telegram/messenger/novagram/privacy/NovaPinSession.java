package org.telegram.messenger.novagram.privacy;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final Runnable EXPIRY_LOCK = NovaPinSession::lockAndShowGate;
    private static Intent pendingLaunch;

    /** Null while unknown; see {@link #pinExists()}. */
    private static volatile Boolean pinPresence;

    /** See {@link #rememberPendingLaunch(Activity)}. */
    private static final int DROPPED_REPLAY_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_FORWARD_RESULT;

    /** Both on the elapsed-realtime clock: it does not go back when the user
     *  changes the time, and it keeps counting while the device sleeps. */
    private static volatile long unlockedAt;
    private static volatile long lastInteractionAt;

    private NovaPinSession() {
    }

    public static boolean isUnlocked() {
        if (NovaDeviceLock.isBlocked()) {
            // Before every other answer, the decoy included: the authorization
            // data on this disk was sealed to a different device, so there is
            // nothing here to unlock and nothing that may be started over it.
            return false;
        }
        if (NovaDecoyState.isActive()) {
            // The decoy has an account record of its own and would otherwise
            // trip the gate. Asking for a PIN there would announce that
            // something was destroyed on this phone, which is the one thing
            // the decoy exists to avoid.
            return true;
        }
        if (noPinToAskFor()) {
            // The application PIN was offered after signing in and the user
            // chose to continue without one, and the vault confirms none was
            // ever created. The gate stays out of the way until a PIN is set
            // from NovaGram settings.
            return true;
        }
        // Asked rather than trusted to the timer. A posted callback does not
        // run while the process is frozen or its main looper is not being
        // served, so an unlock can outlive its deadline; every reader of this
        // answer would then be told the session is still valid.
        if (UNLOCKED.get() && expiredAt(SystemClock.elapsedRealtime())) {
            lock();
        }
        return isAccessAllowed(hasAuthenticatedAccount(), UNLOCKED.get());
    }

    /**
     * Whether there is no PIN on this device to ask for. Two answers, and both
     * have to agree: the preference says the offer was declined, and the vault
     * says nothing was ever enrolled.
     *
     * <p>The preference used to be enough on its own, and it is an ordinary
     * boolean in a plaintext {@code SharedPreferences} file. Writing
     * {@code app_pin_declined=true} into that file disarmed the gate of a user
     * who <em>had</em> set a PIN — and the PIN is the fork's answer to code
     * running as this application on this device, which is precisely the code
     * that can write that file. So "does a PIN exist" is now asked of the
     * Keystore-sealed vault and of nothing else, and the preference decides
     * only whether the enrollment screen is offered again.</p>
     *
     * <p>The order matters as much as the pair does. The preference is read
     * first, and it is false on a fresh install, so a first run never touches
     * the Keystore at all: it falls through to the account check, finds no
     * signed-in account and is allowed in.</p>
     */
    private static boolean noPinToAskFor() {
        return appPinDeclined() && !pinExists();
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

    /**
     * Cached, because {@link #isUnlocked()} runs at every activity start and
     * {@link #armExpiry()} at every touch while an inactivity deadline is set,
     * and the answer costs a Keystore load. It is only ever asked when the
     * declined flag is set, so a user with a PIN pays nothing for it.
     *
     * <p>Anything the vault cannot answer counts as "a PIN exists". The gate
     * reports a corrupt or unreachable vault honestly; letting the caller
     * through instead would make a broken Keystore a second way past the PIN.
     * </p>
     */
    private static boolean pinExists() {
        Boolean cached = pinPresence;
        if (cached != null) {
            return cached;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            // Too early to ask, and deliberately not remembered.
            return true;
        }
        boolean value;
        try {
            value = !new NovaPinVault(context).isAbsent();
        } catch (Throwable ignored) {
            value = true;
        }
        pinPresence = value;
        return value;
    }

    /**
     * A PIN was created or removed. Called by the gate, which is the only place
     * either can happen; the emergency wipe and the device reset also change the
     * vault but end the process, so nothing survives to hold a stale answer.
     */
    public static void onPinStateChanged() {
        pinPresence = null;
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
        unlockedAt = SystemClock.elapsedRealtime();
        lastInteractionAt = unlockedAt;
        armExpiry();
    }

    public static void lock() {
        MAIN_HANDLER.removeCallbacks(BACKGROUND_LOCK);
        MAIN_HANDLER.removeCallbacks(EXPIRY_LOCK);
        UNLOCKED.set(false);
    }

    /**
     * The user did something. Only interesting to the two inactivity options,
     * and cheap enough to call from every touch: it writes a long and, at
     * most, moves one pending callback.
     */
    public static void noteUserInteraction() {
        if (!UNLOCKED.get()) {
            return;
        }
        lastInteractionAt = SystemClock.elapsedRealtime();
        if (policy().inactivityMillis() > 0L) {
            armExpiry();
        }
    }

    /**
     * The policy changed while a session is open. The unlock itself stands -
     * it was granted by a correct PIN and nothing about it became less true -
     * but the deadlines it is measured against are new.
     */
    public static void reevaluatePolicy() {
        if (!UNLOCKED.get()) {
            return;
        }
        MAIN_HANDLER.removeCallbacks(BACKGROUND_LOCK);
        armExpiry();
    }

    /** The device screen went off. */
    public static void onScreenOff() {
        if (UNLOCKED.get() && policy().locksOnScreenOff()) {
            lock();
        }
    }

    public static void scheduleBackgroundLock() {
        MAIN_HANDLER.removeCallbacks(BACKGROUND_LOCK);
        if (policy().locksOnBackground()) {
            // Half a second, as before. Not zero, because a configuration
            // change or an internal activity handoff also passes through
            // background for an instant, and locking on those would ask for
            // the PIN in the middle of the user's own tap.
            MAIN_HANDLER.postDelayed(BACKGROUND_LOCK, 500L);
        }
        // The other options keep their own deadlines running while the
        // application is away - that is the whole difference between them.
        armExpiry();
    }

    public static void cancelBackgroundLock() {
        MAIN_HANDLER.removeCallbacks(BACKGROUND_LOCK);
        // Coming back is not by itself permission to stay: an unlock that
        // expired while the application was away has to be noticed now, and a
        // deadline that is still ahead has to be re-armed.
        if (UNLOCKED.get() && expiredAt(SystemClock.elapsedRealtime())) {
            lock();
            return;
        }
        armExpiry();
    }

    /**
     * Whether a PIN can be asked for at all. Without this the deadlines of the
     * chosen policy went on running after the PIN had been removed, and when
     * one fired the gate opened on its "create a PIN" screen - which cannot be
     * dismissed with the back key. The only way out was "continue without a
     * PIN", which unlocks and therefore armed the very same deadline again,
     * and the row that could have changed the policy is drawn only while a PIN
     * exists. A removal has to stop the clock, not restart it.
     *
     * <p>Deliberately not asking {@link #isUnlocked()}: that answer consults
     * the deadlines, and the deadlines consult this.</p>
     */
    private static boolean pinProtectionActive() {
        return !NovaDecoyState.isActive() && !noPinToAskFor();
    }

    private static NovaPinLockPolicy policy() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return NovaPinLockPolicy.getDefault();
        }
        try {
            return NovaPrivacySettings.global(context).getPinLockPolicy();
        } catch (Throwable ignored) {
            return NovaPinLockPolicy.getDefault();
        }
    }

    private static boolean expiredAt(long now) {
        NovaPinLockPolicy policy = policy();
        long inactivity = policy.inactivityMillis();
        if (inactivity > 0L && now - lastInteractionAt >= inactivity) {
            return true;
        }
        long session = policy.sessionMillis();
        return session > 0L && now - unlockedAt >= session;
    }

    /**
     * Puts one callback on the earliest deadline the current policy has, or
     * none when it has neither. Re-armed rather than accumulated, so there is
     * never more than one pending.
     */
    private static void armExpiry() {
        MAIN_HANDLER.removeCallbacks(EXPIRY_LOCK);
        if (!UNLOCKED.get() || !pinProtectionActive()) {
            return;
        }
        NovaPinLockPolicy policy = policy();
        long now = SystemClock.elapsedRealtime();
        long delay = Long.MAX_VALUE;
        long inactivity = policy.inactivityMillis();
        if (inactivity > 0L) {
            delay = Math.min(delay, lastInteractionAt + inactivity - now);
        }
        long session = policy.sessionMillis();
        if (session > 0L) {
            delay = Math.min(delay, unlockedAt + session - now);
        }
        if (delay == Long.MAX_VALUE) {
            return;
        }
        MAIN_HANDLER.postDelayed(EXPIRY_LOCK, Math.max(0L, delay));
    }

    /**
     * Locking while the application is on screen has to show the gate there
     * and then. Flipping the flag alone would leave the conversation on
     * display until the user happened to navigate somewhere that checks it,
     * which for an inactivity timeout is precisely the wrong moment to be
     * relaxed about.
     */
    private static void lockAndShowGate() {
        lock();
        // Asked after locking, and asked of isUnlocked() rather than of the
        // flag: there is no gate to send anyone to when the PIN has been
        // removed, when the user chose to continue without one, or in the
        // decoy - and sending them anyway is how a removal turned into an
        // undismissable "create a PIN" screen.
        if (isUnlocked()) {
            return;
        }
        Activity activity = LaunchActivity.instance;
        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
            redirectToGate(activity);
        }
    }

    public static synchronized void redirectToGate(Activity activity) {
        if (activity == null) {
            throw new IllegalArgumentException("activity must not be null");
        }
        rememberPendingLaunch(activity);
        Intent gate = new Intent(activity, NovaPinGateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(gate);
        activity.overridePendingTransition(0, 0);
    }

    /**
     * Where the user goes once the PIN has been entered: back to the screen the
     * lock interrupted, started with the Intent that screen was given.
     *
     * <p>The Intent keeps the component it was addressed to. It used to be
     * reclassed to {@link LaunchActivity} — data, extras and flags copied whole
     * and the class simply overwritten. {@code ShareActivity},
     * {@code ExternalActionActivity}, {@code BubbleActivity},
     * {@code PopupNotificationActivity} and {@code VoIPPermissionActivity} are
     * all exported and are guarded through {@link #guardAfterSuper(Activity)},
     * so the Intent being copied there is whatever another application on the
     * phone sent. Handing that to {@code LaunchActivity.handleIntent} is not a
     * replay: that method reads {@code tg://} data, share actions and internal
     * extras that the component the sender actually reached does not, and none
     * of it had been through that component's own handling. Restarting the
     * addressed activity instead does exactly what the system would have done
     * had the application not been locked, and nothing more.</p>
     *
     * <p>The two flag groups that must not survive the trip are dropped. A URI
     * grant belongs to the delivery that carried it and is not re-issued when
     * this process starts the Intent itself, and a forwarded result would be
     * forwarded from the gate, which is not what the sender was answered by.
     * </p>
     */
    private static void rememberPendingLaunch(Activity activity) {
        pendingLaunch = null;
        Intent source = activity.getIntent();
        if (source == null) {
            return;
        }
        Intent copy = new Intent(source);
        copy.setComponent(new ComponentName(activity, activity.getClass()));
        // setFlags rather than removeFlags: the latter arrived in API 26 and
        // this build starts at 23.
        copy.setFlags(source.getFlags() & ~DROPPED_REPLAY_FLAGS);
        pendingLaunch = copy;
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

    /**
     * Deliberately does not reclass what it returns. The component was fixed
     * when the Intent was parked, and forcing {@link LaunchActivity} on it here
     * would put back exactly the substitution
     * {@link #rememberPendingLaunch(Activity)} exists to prevent.
     */
    public static synchronized Intent takePendingLaunch(Activity activity) {
        Intent result = pendingLaunch;
        pendingLaunch = null;
        if (result == null) {
            result = new Intent(activity, LaunchActivity.class);
        }
        return result;
    }
}
