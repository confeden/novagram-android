package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;

/**
 * What NovaGram adds to a Telegram call.
 *
 * <p>Calls were switched off in this fork until there was something to switch
 * on: the stock client asks the server whether it may connect straight to the
 * other side and then obeys the answer, so "nobody may see my IP" was a request
 * rather than a guarantee. {@link #allowP2P} is the guarantee — the server's
 * permission is intersected with the local policy at the one place where the
 * call instance is built, and a "yes" from the server can no longer bring direct
 * connections back.</p>
 *
 * <p>In tgcalls {@code enableP2P = false} makes the port allocator drop UDP and
 * STUN and lose reflexive candidates, so only relay candidates are ever
 * gathered — what WebRTC calls {@code iceTransportPolicy: relay}. Nothing is
 * negotiated with the other side; ICE simply settles on a relay pair.</p>
 */
public final class NovaCallPolicy {
    private NovaCallPolicy() {
    }

    /**
     * Whether calls may be placed at all. A security invariant rather than a
     * setting: it was false while there was no protection and is true now that
     * there is, and either way the user does not get to flip it.
     */
    public static boolean areCallsAllowed(Context context) {
        if (context == null) {
            return false;
        }
        if (NovaDecoyState.isActive(context)) {
            // The decoy has no network at all, and this gate is what turned a
            // call attempt there into Telegram's own "could not connect"
            // instead of a service that starts, hangs and looks wrong. While
            // calls were off for everyone that came for free; now it has to be
            // said out loud, or enabling calls would quietly reopen the decoy.
            return false;
        }
        return NovaPrivacySettings.global(context)
                .isFeatureEnabled(NovaPrivacyFeature.PROTECTED_CALLS);
    }

    public static boolean relayOnly(Context context) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        // No context means no answer from storage, and the safe answer is the
        // one that keeps the address hidden.
        return context == null
                || NovaPrivacySettings.global(context)
                .isFeatureEnabled(NovaPrivacyFeature.CALLS_RELAY_ONLY);
    }

    public static void setRelayOnly(Context context, boolean value) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context != null) {
            NovaPrivacySettings.global(context)
                    .setFeatureEnabled(NovaPrivacyFeature.CALLS_RELAY_ONLY, value);
        }
    }

    /** The single question the call stack is allowed to ask about p2p. */
    public static boolean allowP2P(Context context, boolean serverAllowed) {
        return serverAllowed && !relayOnly(context);
    }

    /**
     * Whether an address handed to the call stack by the server may be used.
     *
     * <p>Reflector addresses arrive numeric and a normal call needs no DNS at
     * all. Nothing checks that, though: the string goes into
     * {@code rtc::SocketAddress} as it is, and a name is left unresolved and
     * handed to {@code getaddrinfo} — the system resolver, in the clear, past
     * every promise this fork makes about DNS. One address chosen by the server
     * would be enough to learn that this account is in a call and from where.
     * So a name is dropped rather than resolved: resolving it over DoH would
     * hide the name and keep the leak of the fact.</p>
     *
     * <p>Parsed by hand rather than with {@code InetAddress}, whose lookup
     * methods fall through to DNS for anything that is not a literal — asking
     * the question would be the thing we are trying to avoid.</p>
     */
    public static boolean acceptableCallAddress(String address) {
        if (address == null || address.length() == 0) {
            return false;
        }
        return address.indexOf(':') >= 0
                ? looksLikeIpv6(address)
                : looksLikeIpv4(address);
    }

    private static boolean looksLikeIpv4(String address) {
        int groups = 0;
        int digits = 0;
        int value = 0;
        for (int i = 0, count = address.length(); i < count; i++) {
            char c = address.charAt(i);
            if (c == '.') {
                if (digits == 0 || ++groups > 3) {
                    return false;
                }
                digits = 0;
                value = 0;
            } else if (c >= '0' && c <= '9') {
                if (++digits > 3) {
                    return false;
                }
                value = value * 10 + (c - '0');
                if (value > 255) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return groups == 3 && digits > 0;
    }

    private static boolean looksLikeIpv6(String address) {
        // Deliberately permissive about the shape and strict about the
        // alphabet: what has to be impossible is a name reaching the resolver,
        // and a malformed literal is refused by the socket layer anyway. A zone
        // index is refused too — it names an interface of this machine and has
        // no business coming from a server.
        int colons = 0;
        for (int i = 0, count = address.length(); i < count; i++) {
            char c = address.charAt(i);
            if (c == ':') {
                colons++;
            } else if (c == '.'
                    || (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F')) {
                continue;
            } else {
                return false;
            }
        }
        return colons >= 2 && colons <= 7;
    }
}
