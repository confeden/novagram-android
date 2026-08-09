package org.telegram.messenger.novagram.privacy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NovaPrivacyContractTest {
    @Test
    public void retentionBoundsMatchPublicContract() {
        assertEquals(24, NovaPrivacyContract.requireRetentionHours(24));
        assertEquals(168, NovaPrivacyContract.requireRetentionHours(168));
        assertFalse(NovaPrivacyContract.isValidRetentionHours(23));
        assertFalse(NovaPrivacyContract.isValidRetentionHours(169));

        expectIllegalArgument(() -> NovaPrivacyContract.requireRetentionHours(23));
        expectIllegalArgument(() -> NovaPrivacyContract.requireRetentionHours(169));
    }

    @Test
    public void pinValidationAcceptsOnlyFourToSixDigits() {
        assertTrue(NovaPrivacyContract.isValidPin("0000"));
        assertTrue(NovaPrivacyContract.isValidPin("123456"));
        assertTrue(NovaPrivacyContract.isValidPin(new char[]{'0', '0', '0', '0'}));
        assertFalse(NovaPrivacyContract.isValidPin("123"));
        assertFalse(NovaPrivacyContract.isValidPin("1234567"));
        assertFalse(NovaPrivacyContract.isValidPin("12a4"));
        assertFalse(NovaPrivacyContract.isValidPin((CharSequence) null));
        assertFalse(NovaPrivacyContract.isValidPin(new char[]{'1', '2', 'a', '4'}));
    }

    @Test
    public void thirdPinFailureStartsFiveMinuteDelayAndCapsAtOneDay() {
        assertEquals(0L, NovaPrivacyContract.getPinRetryDelayMillis(0));
        assertEquals(0L, NovaPrivacyContract.getPinRetryDelayMillis(1));
        assertEquals(0L, NovaPrivacyContract.getPinRetryDelayMillis(2));
        assertEquals(5L * 60L * 1000L, NovaPrivacyContract.getPinRetryDelayMillis(3));
        assertEquals(10L * 60L * 1000L, NovaPrivacyContract.getPinRetryDelayMillis(4));
        assertEquals(24L * 60L * 60L * 1000L, NovaPrivacyContract.getPinRetryDelayMillis(Integer.MAX_VALUE));
        expectIllegalArgument(() -> NovaPrivacyContract.getPinRetryDelayMillis(-1));
    }

    @Test
    public void privacyDefaultsAreExplicit() {
        for (NovaPrivacyFeature feature : NovaPrivacyFeature.values()) {
            if (feature == NovaPrivacyFeature.PROTECTED_CALLS) {
                assertFalse(feature.isDefaultEnabled());
            } else {
                assertTrue(feature.name(), feature.isDefaultEnabled());
            }
        }
        assertFalse(NovaPrivacyFeature.FIRST_RUN_PIN.isUserConfigurable());
        assertFalse(NovaPrivacyFeature.STRICT_ENCRYPTED_DNS.isUserConfigurable());
        assertFalse(NovaPrivacyFeature.PROTECTED_CALLS.isUserConfigurable());
    }

    @Test
    public void encryptedDnsOrderAndEndpointsMatchPublicContract() {
        assertEquals(NovaPrivacyContract.DohProvider.CLOUDFLARE, NovaPrivacyContract.DEFAULT_DOH_ORDER.get(0));
        assertEquals(NovaPrivacyContract.DohProvider.GOOGLE, NovaPrivacyContract.DEFAULT_DOH_ORDER.get(1));
        assertEquals(NovaPrivacyContract.DohProvider.ADGUARD, NovaPrivacyContract.DEFAULT_DOH_ORDER.get(2));
        assertEquals(NovaPrivacyContract.DohProvider.QUAD9, NovaPrivacyContract.DEFAULT_DOH_ORDER.get(3));
        assertEquals("https://dns.adguard-dns.com/dns-query",
                NovaPrivacyContract.DohProvider.ADGUARD.getEndpoint());
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
