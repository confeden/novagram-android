/*
 * NovaGram addition to the tgnet library. See NovaConfigSeal.h.
 */

#include "NovaConfigSeal.h"
#include "FileLog.h"

#include <atomic>
#include <cstring>
#include <mutex>
#include <openssl/aead.h>
#include <openssl/rand.h>

namespace {

    const uint8_t MAGIC[] = { 'N', 'V', 'C', '1' };
    const size_t MAGIC_SIZE = sizeof(MAGIC);
    const size_t KEY_SIZE = 32;
    const size_t NONCE_SIZE = 12;
    const size_t TAG_SIZE = 16;
    const size_t HEADER_SIZE = MAGIC_SIZE + NONCE_SIZE;

    // The key is written from whichever thread Java calls in on and read by the
    // network thread inside seal()/open(). Without the mutex a setKey() landing
    // mid-seal - which is exactly what the emergency wipe's forget() does -
    // could produce a file sealed with half a key, openable by nobody, owner
    // included.
    std::mutex keyMutex;
    uint8_t currentKey[KEY_SIZE];
    bool currentKeySet = false;
    bool sealWritesEnabled = false;

    // Written by the network thread, read from the JNI and wipe threads.
    std::atomic<bool> foreignConfig(false);
    std::atomic<bool> writesStopped(false);

}

namespace NovaConfigSeal {

    void setKey(const uint8_t *key, size_t size, bool sealWrites) {
        std::lock_guard<std::mutex> guard(keyMutex);
        if (key == nullptr || size != KEY_SIZE) {
            memset(currentKey, 0, KEY_SIZE);
            currentKeySet = false;
            sealWritesEnabled = false;
            return;
        }
        memcpy(currentKey, key, KEY_SIZE);
        currentKeySet = true;
        sealWritesEnabled = sealWrites;
    }

    bool hasKey() {
        std::lock_guard<std::mutex> guard(keyMutex);
        return currentKeySet;
    }

    bool sealsWrites() {
        std::lock_guard<std::mutex> guard(keyMutex);
        return currentKeySet && sealWritesEnabled;
    }

    size_t magicSize() {
        return MAGIC_SIZE;
    }

    bool startsWithMagic(const uint8_t *data, size_t size) {
        return data != nullptr
               && size >= MAGIC_SIZE
               && memcmp(data, MAGIC, MAGIC_SIZE) == 0;
    }

    bool isSealed(const uint8_t *data, size_t size) {
        return startsWithMagic(data, size) && size > HEADER_SIZE + TAG_SIZE;
    }

    bool seal(const uint8_t *plain, size_t size, std::vector<uint8_t> &out) {
        // Held for the whole operation, not just for the read of the flag: the
        // key is handed to BoringSSL by pointer and must not be rewritten
        // underneath it.
        std::lock_guard<std::mutex> guard(keyMutex);
        if (!currentKeySet || plain == nullptr || size == 0) {
            return false;
        }
        const EVP_AEAD *aead = EVP_aead_aes_256_gcm();
        EVP_AEAD_CTX *context = EVP_AEAD_CTX_new(
                aead,
                currentKey,
                KEY_SIZE,
                EVP_AEAD_DEFAULT_TAG_LENGTH
        );
        if (context == nullptr) {
            return false;
        }
        std::vector<uint8_t> result(HEADER_SIZE + size + EVP_AEAD_max_overhead(aead));
        memcpy(result.data(), MAGIC, MAGIC_SIZE);
        bool ok = RAND_bytes(result.data() + MAGIC_SIZE, NONCE_SIZE) == 1;
        size_t sealedLength = 0;
        if (ok) {
            ok = EVP_AEAD_CTX_seal(
                    context,
                    result.data() + HEADER_SIZE,
                    &sealedLength,
                    result.size() - HEADER_SIZE,
                    result.data() + MAGIC_SIZE,
                    NONCE_SIZE,
                    plain,
                    size,
                    MAGIC,
                    MAGIC_SIZE
            ) == 1;
        }
        EVP_AEAD_CTX_free(context);
        if (!ok) {
            return false;
        }
        result.resize(HEADER_SIZE + sealedLength);
        out.swap(result);
        return true;
    }

    bool open(const uint8_t *sealed, size_t size, std::vector<uint8_t> &out, bool reportForeign) {
        if (!startsWithMagic(sealed, size)) {
            return false;
        }
        if (size <= HEADER_SIZE + TAG_SIZE) {
            // Carries the seal but is too short to hold one. Truncated or cut
            // off mid-write elsewhere; either way there is nothing here this
            // device may read, and it must not be mistaken for a plain config
            // and parsed as one.
            if (reportForeign) {
                foreignConfig = true;
            }
            if (LOGS_ENABLED) DEBUG_E("NovaGram: tgnet config carries the seal but is too short to hold one");
            return false;
        }
        // Same as seal(): the key stays put for the whole open.
        std::lock_guard<std::mutex> guard(keyMutex);
        if (!currentKeySet) {
            if (reportForeign) {
                foreignConfig = true;
            }
            if (LOGS_ENABLED) DEBUG_E("NovaGram: tgnet config is sealed and this device has no key for it");
            return false;
        }
        const EVP_AEAD *aead = EVP_aead_aes_256_gcm();
        EVP_AEAD_CTX *context = EVP_AEAD_CTX_new(
                aead,
                currentKey,
                KEY_SIZE,
                EVP_AEAD_DEFAULT_TAG_LENGTH
        );
        if (context == nullptr) {
            return false;
        }
        const size_t bodySize = size - HEADER_SIZE;
        std::vector<uint8_t> result(bodySize);
        size_t plainLength = 0;
        bool ok = EVP_AEAD_CTX_open(
                context,
                result.data(),
                &plainLength,
                result.size(),
                sealed + MAGIC_SIZE,
                NONCE_SIZE,
                sealed + HEADER_SIZE,
                bodySize,
                MAGIC,
                MAGIC_SIZE
        ) == 1;
        EVP_AEAD_CTX_free(context);
        if (!ok || plainLength == 0) {
            if (reportForeign) {
                foreignConfig = true;
            }
            if (LOGS_ENABLED) DEBUG_E("NovaGram: tgnet config does not belong to this device");
            return false;
        }
        result.resize(plainLength);
        out.swap(result);
        return true;
    }

    bool metForeignConfig() {
        return foreignConfig;
    }

    void clearForeignConfig() {
        foreignConfig = false;
    }

    void forbidWrites() {
        writesStopped = true;
    }

    bool writesForbidden() {
        return writesStopped;
    }

}
