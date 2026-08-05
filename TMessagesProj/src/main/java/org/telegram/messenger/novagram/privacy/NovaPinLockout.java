package org.telegram.messenger.novagram.privacy;

/**
 * Pure state machine for PIN retry delays.
 *
 * <p>The caller persists {@link State} before exposing a failed verification
 * result. Wall-clock time is deliberately not accepted by this class.</p>
 */
public final class NovaPinLockout {
    public static final int UNKNOWN_BOOT_COUNT = -1;

    private NovaPinLockout() {
    }

    public static Evaluation evaluate(State state, long nowElapsedRealtime, int currentBootCount) {
        requireElapsedRealtime(nowElapsedRealtime);
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (state.lockoutDeadlineElapsedRealtime == 0L) {
            return new Evaluation(state, 0L, false);
        }

        long fullDelay = NovaPrivacyContract.getPinRetryDelayMillis(state.failedAttempts);
        if (fullDelay == 0L) {
            State repaired = State.unlocked(state.failedAttempts);
            return new Evaluation(repaired, 0L, true);
        }

        boolean bootIdentityChanged = state.bootCount != currentBootCount;
        boolean monotonicClockRolledBack = nowElapsedRealtime < state.lockoutStartedElapsedRealtime;
        if (bootIdentityChanged || monotonicClockRolledBack) {
            State restarted = new State(
                    state.failedAttempts,
                    nowElapsedRealtime,
                    safeAdd(nowElapsedRealtime, fullDelay),
                    currentBootCount
            );
            return new Evaluation(restarted, fullDelay, true);
        }

        if (nowElapsedRealtime < state.lockoutDeadlineElapsedRealtime) {
            return new Evaluation(
                    state,
                    state.lockoutDeadlineElapsedRealtime - nowElapsedRealtime,
                    false
            );
        }
        return new Evaluation(state, 0L, false);
    }

    public static State afterFailure(State state, long nowElapsedRealtime, int currentBootCount) {
        requireElapsedRealtime(nowElapsedRealtime);
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        int failedAttempts = state.failedAttempts == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : state.failedAttempts + 1;
        long delay = NovaPrivacyContract.getPinRetryDelayMillis(failedAttempts);
        if (delay == 0L) {
            return State.unlocked(failedAttempts);
        }
        return new State(
                failedAttempts,
                nowElapsedRealtime,
                safeAdd(nowElapsedRealtime, delay),
                currentBootCount
        );
    }

    public static State afterSuccess() {
        return State.unlocked(0);
    }

    private static void requireElapsedRealtime(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("elapsed realtime must not be negative");
        }
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public static final class State {
        private final int failedAttempts;
        private final long lockoutStartedElapsedRealtime;
        private final long lockoutDeadlineElapsedRealtime;
        private final int bootCount;

        public State(
                int failedAttempts,
                long lockoutStartedElapsedRealtime,
                long lockoutDeadlineElapsedRealtime,
                int bootCount
        ) {
            if (failedAttempts < 0) {
                throw new IllegalArgumentException("failedAttempts must not be negative");
            }
            if (lockoutStartedElapsedRealtime < 0L || lockoutDeadlineElapsedRealtime < 0L) {
                throw new IllegalArgumentException("lockout timestamps must not be negative");
            }
            if (lockoutDeadlineElapsedRealtime != 0L
                    && lockoutDeadlineElapsedRealtime < lockoutStartedElapsedRealtime) {
                throw new IllegalArgumentException("lockout deadline precedes its start");
            }
            this.failedAttempts = failedAttempts;
            this.lockoutStartedElapsedRealtime = lockoutStartedElapsedRealtime;
            this.lockoutDeadlineElapsedRealtime = lockoutDeadlineElapsedRealtime;
            this.bootCount = bootCount;
        }

        public static State initial() {
            return unlocked(0);
        }

        public static State unlocked(int failedAttempts) {
            return new State(failedAttempts, 0L, 0L, UNKNOWN_BOOT_COUNT);
        }

        public int getFailedAttempts() {
            return failedAttempts;
        }

        public long getLockoutStartedElapsedRealtime() {
            return lockoutStartedElapsedRealtime;
        }

        public long getLockoutDeadlineElapsedRealtime() {
            return lockoutDeadlineElapsedRealtime;
        }

        public int getBootCount() {
            return bootCount;
        }
    }

    public static final class Evaluation {
        private final State state;
        private final long remainingMillis;
        private final boolean stateChanged;

        private Evaluation(State state, long remainingMillis, boolean stateChanged) {
            this.state = state;
            this.remainingMillis = remainingMillis;
            this.stateChanged = stateChanged;
        }

        public State getState() {
            return state;
        }

        public long getRemainingMillis() {
            return remainingMillis;
        }

        public boolean isLocked() {
            return remainingMillis > 0L;
        }

        public boolean isStateChanged() {
            return stateChanged;
        }
    }
}
