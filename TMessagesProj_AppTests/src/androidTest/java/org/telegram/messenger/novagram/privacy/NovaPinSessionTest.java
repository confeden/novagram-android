package org.telegram.messenger.novagram.privacy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NovaPinSessionTest {
    @Test
    public void signedOutApplicationNeverRequiresPinUnlock() {
        assertTrue(NovaPinSession.isAccessAllowed(false, false));
        assertTrue(NovaPinSession.isAccessAllowed(false, true));
    }

    @Test
    public void authenticatedApplicationRequiresUnlockedSession() {
        assertFalse(NovaPinSession.isAccessAllowed(true, false));
        assertTrue(NovaPinSession.isAccessAllowed(true, true));
    }
}
