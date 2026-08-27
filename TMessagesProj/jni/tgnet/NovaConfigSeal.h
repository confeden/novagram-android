/*
 * NovaGram addition to the tgnet library.
 *
 * tgnet.dat holds the datacenter authorization keys - the whole of what it
 * takes to be signed in. Upstream writes it in the clear, which is why a
 * copied application data directory signs in on another device with nothing
 * asked. Here those bytes are sealed with a key that lives in the Android
 * Keystore and never leaves the device it was created on.
 */

#ifndef NOVACONFIGSEAL_H
#define NOVACONFIGSEAL_H

#include <cstddef>
#include <cstdint>
#include <vector>

namespace NovaConfigSeal {

    // Handed over from Java before the first Config is built. Exactly 32 bytes,
    // or a null pointer to say there is none: without a key the file keeps the
    // upstream layout, so nothing is lost when binding is off or unavailable.
    //
    // sealWrites is a separate answer on purpose. A key is still needed to open
    // a file that was sealed before the owner switched binding off - it is what
    // lets the next write put the contents back in the clear.
    void setKey(const uint8_t *key, size_t size, bool sealWrites);

    // Can open a sealed file.
    bool hasKey();

    // Must seal what it writes.
    bool sealsWrites();

    // How many leading bytes carry the magic. Whoever peeks at a file has to
    // read at least this many, and asking rather than assuming four is what
    // stops a longer magic from silently turning every downgrade check into a
    // no-op - a failure that would look exactly like everything working.
    size_t magicSize();

    // True when these leading bytes carry the seal magic. Answers "this file
    // claims to be sealed" and nothing more, so it can be asked of a peek at a
    // file that is already on disk without reading all of it.
    bool startsWithMagic(const uint8_t *data, size_t size);

    // True when the stored bytes carry the seal and are long enough to hold
    // one. Asked of the file, never of the setting: a file written on a device
    // that had binding on stays sealed no matter what this installation
    // prefers.
    bool isSealed(const uint8_t *data, size_t size);

    // Both leave the output untouched and return false when they cannot do the
    // work. A failed open is not an empty config: the caller must answer "no
    // config", so that nothing signs in with an authorization it could not read.
    //
    // reportForeign says whether a failure here means the data directory was
    // carried in from another device. True only for the file that holds the
    // authorization keys: the library builds Configs for per-datacenter address
    // indices and CDN public keys as well, and one of those left sealed - by a
    // binding switched off, which rewrites tgnet.dat and not them - is no
    // evidence of anything and must not put the client on the blocked screen
    // while its actual authorization sits there perfectly readable.
    bool seal(const uint8_t *plain, size_t size, std::vector<uint8_t> &out);
    bool open(const uint8_t *sealed, size_t size, std::vector<uint8_t> &out, bool reportForeign);

    // Set once a sealed file was met that could not be opened, and sticky for
    // the life of the process. This is the fork's whole evidence that the data
    // directory was carried here from somewhere else (I14: detection is driven
    // by the ciphertext, not by the setting), so it decides two things:
    //
    //  - Config::writeConfig refuses to touch a sealed file while it is set,
    //    because D13 says an unsealable copy is never overwritten automatically
    //    - the client blocks and the owner asks for the one explicit start over;
    //  - Java reads it after native_init and shows the "another device" screen.
    //
    // Deliberately not cleared by setKey(): a key is installed once per account
    // before that account's config is read, and clearing there would throw away
    // the verdict of the account read just before.
    bool metForeignConfig();
    void clearForeignConfig();

    // One-way, for the emergency wipe. From the moment it is called no Config
    // is written again in this process, sealed or not: the wipe has to be able
    // to destroy the device key without a saveConfig() landing between the two
    // and putting the datacenter authorization keys on disk in the clear (N15).
    // Both callers end the process, so there is nothing to switch back on.
    void forbidWrites();
    bool writesForbidden();

}

#endif // NOVACONFIGSEAL_H
