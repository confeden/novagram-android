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

    // True when the stored bytes carry the seal. Asked of the file, never of
    // the setting: a file written on a device that had binding on stays sealed
    // no matter what this installation prefers.
    bool isSealed(const uint8_t *data, size_t size);

    // Both leave the output untouched and return false when they cannot do the
    // work. A failed open is not an empty config: the caller must answer "no
    // config", so that nothing signs in with an authorization it could not read.
    bool seal(const uint8_t *plain, size_t size, std::vector<uint8_t> &out);
    bool open(const uint8_t *sealed, size_t size, std::vector<uint8_t> &out);

    // Set once a sealed file was met that could not be opened. Java uses it
    // only for the log: the same conclusion is reached there before any of this
    // runs, by looking at the same first bytes.
    bool metForeignConfig();

}

#endif // NOVACONFIGSEAL_H
