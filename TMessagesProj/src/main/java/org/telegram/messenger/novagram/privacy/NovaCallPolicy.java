package org.telegram.messenger.novagram.privacy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

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

    /**
     * The same rule, applied to the join response of a group or conference
     * call.
     *
     * <p>Those calls take their addresses from a JSON payload the server
     * sends, not from {@code phoneConnection}, so the filter around
     * {@code Instance.Endpoint} never sees them. Each {@code candidate.ip}
     * goes into {@code rtc::SocketAddress} inside tgcalls exactly like a
     * reflector address does, and a name there is left unresolved and handed
     * to {@code getaddrinfo}. It is the only way a group call can produce a
     * DNS query at all.</p>
     *
     * <p>Not gated on {@link #relayOnly}: no call of any kind needs DNS, so a
     * name is dropped whatever the setting says — the same as for one to
     * one. Returns the payload untouched when there is nothing to drop, so
     * the stock path stays byte for byte what the server sent.</p>
     */
    public static String filterJoinResponseAddresses(String payload) {
        if (payload == null || payload.length() == 0) {
            return payload;
        }
        try {
            final JSONObject root = new JSONObject(payload);
            final JSONObject transport = root.optJSONObject("transport");
            if (transport == null) {
                // A stream payload carries no transport, so no addresses.
                return payload;
            }
            final JSONArray candidates = transport.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                return payload;
            }
            final JSONArray accepted = new JSONArray();
            for (int i = 0; i < candidates.length(); i++) {
                final JSONObject candidate = candidates.optJSONObject(i);
                final String address = candidate == null
                        ? null
                        : candidate.optString("ip", null);
                if (acceptableCallAddress(address)) {
                    accepted.put(candidate);
                } else {
                    FileLog.w("novagram: refused group call address '"
                            + address + "', not a numeric one");
                }
            }
            if (accepted.length() == candidates.length()) {
                return payload;
            }
            if (accepted.length() == 0) {
                // The call will not connect now, and without this line the
                // reason is indistinguishable from a network failure.
                FileLog.e("novagram: refused every address the group call "
                        + "server sent");
            }
            transport.put("candidates", accepted);
            root.put("transport", transport);
            final String filtered = root.toString();
            if (filtered == null || filtered.length() == 0) {
                // Cannot happen with an object we have just parsed, but the
                // fallback has to be the safe direction rather than the
                // original payload: a payload without a transport is refused
                // by the native side and asks nobody for a name.
                FileLog.e("novagram: could not rebuild the join response");
                return "{}";
            }
            return filtered;
        } catch (Exception e) {
            // A payload we cannot read is one the native side cannot read
            // either - it refuses to parse it and adds no candidate at all,
            // so passing it on leaks nothing.
            FileLog.e(e);
            return payload;
        }
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
