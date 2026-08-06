package org.telegram.messenger.novagram.privacy;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NovaScreenshotPolicyTest {
    @Test
    public void adaptivePolicyBlocksUsersAndUnknownPeers() {
        assertTrue(NovaScreenshotPolicy.shouldSecureChat(
                NovaPrivacyContract.ScreenshotPolicy.ADAPTIVE,
                null,
                new TLRPC.TL_user(),
                null
        ));
        assertTrue(NovaScreenshotPolicy.shouldSecureChat(
                NovaPrivacyContract.ScreenshotPolicy.ADAPTIVE,
                null,
                null,
                null
        ));
    }

    @Test
    public void adaptivePolicyBlocksPrivateGroupsAndAllowsPublicGroups() {
        TLRPC.TL_channel privateGroup = new TLRPC.TL_channel();
        privateGroup.megagroup = true;
        assertTrue(NovaScreenshotPolicy.shouldSecureChat(
                NovaPrivacyContract.ScreenshotPolicy.ADAPTIVE,
                privateGroup,
                null,
                null
        ));

        TLRPC.TL_channel publicGroup = new TLRPC.TL_channel();
        publicGroup.megagroup = true;
        publicGroup.username = "novagram_public_test";
        assertFalse(NovaScreenshotPolicy.shouldSecureChat(
                NovaPrivacyContract.ScreenshotPolicy.ADAPTIVE,
                publicGroup,
                null,
                null
        ));
    }

    @Test
    public void explicitPoliciesOverrideAdaptiveClassification() {
        TLRPC.TL_channel publicChannel = new TLRPC.TL_channel();
        publicChannel.broadcast = true;
        publicChannel.username = "novagram_public_channel";
        assertTrue(NovaScreenshotPolicy.shouldSecureChat(
                NovaPrivacyContract.ScreenshotPolicy.BLOCK_ALL_CHATS,
                publicChannel,
                null,
                null
        ));
        assertFalse(NovaScreenshotPolicy.shouldSecureChat(
                NovaPrivacyContract.ScreenshotPolicy.ALLOW_ALL,
                null,
                new TLRPC.TL_user(),
                null
        ));
    }
}
