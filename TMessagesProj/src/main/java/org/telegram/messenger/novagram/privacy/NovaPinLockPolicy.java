package org.telegram.messenger.novagram.privacy;

import java.util.concurrent.TimeUnit;

/**
 * When the application PIN is asked for again after it has been entered once.
 *
 * <p>Until 2026-08-10 there was no choice: the session was dropped half a
 * second after the application went to the background, which is
 * {@link #ON_MINIMIZE} below. That is the safest answer and stays the default,
 * but it is also the most tiring one - switching to the camera to send a photo
 * costs a PIN entry - and a protection people turn off entirely because it is
 * exhausting protects nothing.</p>
 *
 * <p>The same five choices exist on the desktop fork, spelled with the same
 * storage keys, so one line in the roadmap describes both platforms.</p>
 *
 * <p>Every option is an answer to one question: when does an unlock stop being
 * valid. The three shapes an answer can take are a event ({@link
 * #locksOnBackground}, {@link #locksOnScreenOff}), a stretch of doing nothing
 * ({@link #inactivityMillis}) and a hard ceiling on the age of the unlock
 * ({@link #sessionMillis}). An option is any combination of the three, and the
 * session code does not need to know which option it is serving.</p>
 */
public enum NovaPinLockPolicy {

    /** The session ends as soon as the application leaves the screen. */
    ON_MINIMIZE("on_minimize"),

    /**
     * The session survives switching apps and ends when the device is locked.
     * Leaving the application open on an unlocked phone leaves it open.
     */
    ON_SCREEN_LOCK("on_screen_lock"),

    /** The session ends ten minutes after the last thing the user did. */
    INACTIVITY_10M("inactivity_10m"),

    /** The same, an hour. */
    INACTIVITY_60M("inactivity_60m"),

    /**
     * The PIN is asked only when the application is started. A process that is
     * never killed would otherwise never ask again, so an unlock also expires
     * a day after it was granted - the weakest option here, and it says so in
     * its own description.
     */
    ON_START("on_start");

    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);

    private final String storageKey;

    NovaPinLockPolicy(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getStorageKey() {
        return storageKey;
    }

    /**
     * Keys are matched, never ordinals: the order of the constants is a
     * presentation detail and renaming or reordering them must not silently
     * turn one stored answer into another.
     */
    public static NovaPinLockPolicy fromStorageKey(String key, NovaPinLockPolicy fallback) {
        if (key != null) {
            for (NovaPinLockPolicy policy : values()) {
                if (policy.storageKey.equals(key)) {
                    return policy;
                }
            }
        }
        return fallback;
    }

    public static NovaPinLockPolicy getDefault() {
        // Locking the device is the moment a phone actually stops being under
        // its owner's eyes. Minimising is stricter still, and was the only
        // behaviour before there was a choice, but it costs a PIN entry every
        // time the camera is opened to send a photo - and a protection people
        // switch off because it exhausts them protects nothing.
        return ON_SCREEN_LOCK;
    }

    public boolean locksOnBackground() {
        return this == ON_MINIMIZE;
    }

    public boolean locksOnScreenOff() {
        // Backgrounding is not screen-off, but screen-off while the
        // application is on top is: locking the phone with NovaGram open is
        // exactly the case this option is chosen for.
        return this == ON_SCREEN_LOCK;
    }

    /** Zero when doing nothing never ends the session by itself. */
    public long inactivityMillis() {
        if (this == INACTIVITY_10M) {
            return TimeUnit.MINUTES.toMillis(10);
        } else if (this == INACTIVITY_60M) {
            return TimeUnit.MINUTES.toMillis(60);
        }
        return 0L;
    }

    /** Zero when the age of the unlock alone never ends the session. */
    public long sessionMillis() {
        return (this == ON_START) ? DAY_MILLIS : 0L;
    }
}
