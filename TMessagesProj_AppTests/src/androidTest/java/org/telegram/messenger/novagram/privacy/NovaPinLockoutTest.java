package org.telegram.messenger.novagram.privacy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NovaPinLockoutTest {
    @Test
    public void thirdFailureStartsFiveMinuteLockout() {
        NovaPinLockout.State state = NovaPinLockout.State.initial();
        state = NovaPinLockout.afterFailure(state, 1_000L, 10);
        state = NovaPinLockout.afterFailure(state, 2_000L, 10);
        assertEquals(0L, state.getLockoutDeadlineElapsedRealtime());

        state = NovaPinLockout.afterFailure(state, 3_000L, 10);
        assertEquals(3, state.getFailedAttempts());
        assertEquals(5L * 60L * 1000L, state.getLockoutDeadlineElapsedRealtime() - 3_000L);
    }

    @Test
    public void activeLockoutUsesMonotonicTime() {
        NovaPinLockout.State state = new NovaPinLockout.State(3, 10_000L, 310_000L, 7);
        NovaPinLockout.Evaluation evaluation = NovaPinLockout.evaluate(state, 70_000L, 7);
        assertTrue(evaluation.isLocked());
        assertFalse(evaluation.isStateChanged());
        assertEquals(240_000L, evaluation.getRemainingMillis());
    }

    @Test
    public void rebootRestartsFullCurrentDelay() {
        NovaPinLockout.State state = new NovaPinLockout.State(4, 10_000L, 610_000L, 7);
        NovaPinLockout.Evaluation evaluation = NovaPinLockout.evaluate(state, 4_000L, 8);
        assertTrue(evaluation.isLocked());
        assertTrue(evaluation.isStateChanged());
        assertEquals(10L * 60L * 1000L, evaluation.getRemainingMillis());
        assertEquals(8, evaluation.getState().getBootCount());
    }

    @Test
    public void monotonicRollbackFailsClosed() {
        NovaPinLockout.State state = new NovaPinLockout.State(3, 20_000L, 320_000L, 9);
        NovaPinLockout.Evaluation evaluation = NovaPinLockout.evaluate(state, 1_000L, 9);
        assertTrue(evaluation.isLocked());
        assertTrue(evaluation.isStateChanged());
        assertEquals(5L * 60L * 1000L, evaluation.getRemainingMillis());
    }

    @Test
    public void successfulVerificationResetsAllRetryState() {
        NovaPinLockout.State reset = NovaPinLockout.afterSuccess();
        assertEquals(0, reset.getFailedAttempts());
        assertEquals(0L, reset.getLockoutDeadlineElapsedRealtime());
        assertEquals(NovaPinLockout.UNKNOWN_BOOT_COUNT, reset.getBootCount());
    }

    @Test
    public void delayNeverExceedsTwentyFourHours() {
        NovaPinLockout.State state = new NovaPinLockout.State(
                Integer.MAX_VALUE,
                1_000L,
                1_000L + NovaPrivacyContract.MAX_PIN_DELAY_MILLIS,
                1
        );
        NovaPinLockout.State next = NovaPinLockout.afterFailure(state, 2_000L, 1);
        assertEquals(Integer.MAX_VALUE, next.getFailedAttempts());
        assertEquals(
                NovaPrivacyContract.MAX_PIN_DELAY_MILLIS,
                next.getLockoutDeadlineElapsedRealtime() - 2_000L
        );
    }
}
