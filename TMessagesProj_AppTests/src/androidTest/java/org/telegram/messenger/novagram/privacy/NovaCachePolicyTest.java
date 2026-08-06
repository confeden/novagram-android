package org.telegram.messenger.novagram.privacy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NovaCachePolicyTest {
    @Test
    public void defaultRetentionExpiresAfterExactlySevenDays() {
        long created = 1_700_000_000_000L;
        long expires = NovaCachePolicy.expirationFor(
                created,
                NovaPrivacyContract.DEFAULT_RETENTION_HOURS
        );
        assertEquals(168L * NovaCachePolicy.HOUR_MILLIS, expires - created);
        assertFalse(NovaCachePolicy.isExpired(created, expires, expires - 1L));
        assertTrue(NovaCachePolicy.isExpired(created, expires, expires));
    }

    @Test(expected = IllegalArgumentException.class)
    public void retentionBelowTwentyFourHoursIsRejected() {
        NovaCachePolicy.expirationFor(1_700_000_000_000L, 23);
    }

    @Test(expected = IllegalArgumentException.class)
    public void retentionAboveOneHundredSixtyEightHoursIsRejected() {
        NovaCachePolicy.expirationFor(1_700_000_000_000L, 169);
    }

    @Test
    public void wallClockRollbackFailsClosed() {
        long created = 1_700_000_000_000L;
        long expires = NovaCachePolicy.expirationFor(created, 24);
        assertTrue(NovaCachePolicy.isExpired(created, expires, created - 1L));
    }

    @Test
    public void malformedAuthenticatedMetadataFailsClosed() {
        long created = 1_700_000_000_000L;
        assertTrue(NovaCachePolicy.isExpired(created, created + 1L, created));
        assertTrue(NovaCachePolicy.isExpired(created, created - 1L, created));
    }

    @Test
    public void onlyCanonicalOpaqueBlobIdsAreAccepted() {
        assertTrue(NovaCachePolicy.isValidBlobId("00112233445566778899aabbccddeeff"));
        assertFalse(NovaCachePolicy.isValidBlobId("../00112233445566778899aabbccddeeff"));
        assertFalse(NovaCachePolicy.isValidBlobId("00112233445566778899AABBCCDDEEFF"));
        assertFalse(NovaCachePolicy.isValidBlobId("001122"));
    }
}
