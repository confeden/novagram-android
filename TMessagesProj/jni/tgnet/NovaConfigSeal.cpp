/*
 * NovaGram addition to the tgnet library. See NovaConfigSeal.h.
 */

#include "NovaConfigSeal.h"
#include "FileLog.h"

#include <cstring>
#include <openssl/aead.h>
#include <openssl/rand.h>

namespace {

    const uint8_t MAGIC[] = { 'N', 'V', 'C', '1' };
    const size_t MAGIC_SIZE = sizeof(MAGIC);
    const size_t KEY_SIZE = 32;
    const size_t NONCE_SIZE = 12;
    const size_t TAG_SIZE = 16;
    const size_t HEADER_SIZE = MAGIC_SIZE + NONCE_SIZE;

    uint8_t currentKey[KEY_SIZE];
    bool currentKeySet = false;
    bool sealWritesEnabled = false;
    bool foreignConfig = false;

}

namespace NovaConfigSeal {

    void setKey(const uint8_t *key, size_t size, bool sealWrites) {
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
        return currentKeySet;
    }

    bool sealsWrites() {
        return currentKeySet && sealWritesEnabled;
    }

    bool isSealed(const uint8_t *data, size_t size) {
        return data != nullptr
               && size > HEADER_SIZE + TAG_SIZE
               && memcmp(data, MAGIC, MAGIC_SIZE) == 0;
    }

    bool seal(const uint8_t *plain, size_t size, std::vector<uint8_t> &out) {
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

    bool open(const uint8_t *sealed, size_t size, std::vector<uint8_t> &out) {
        if (!isSealed(sealed, size)) {
            return false;
        }
        if (!currentKeySet) {
            foreignConfig = true;
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
            foreignConfig = true;
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

}
