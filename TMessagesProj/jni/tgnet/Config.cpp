/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#include <sys/stat.h>
#include <unistd.h>
#include <errno.h>
#include <cstring>
#include <vector>
#include "Config.h"
#include "ConnectionsManager.h"
#include "FileLog.h"
#include "BuffersStorage.h"
#include "NovaConfigSeal.h"

Config::Config(int32_t instance, std::string fileName) {
    instanceNum = instance;
    // NovaGram: the library builds a Config for several files. Only this one
    // holds the authorization keys, so only this one is evidence that the
    // directory came from another device. The per-datacenter address indices
    // and the CDN public keys are public routing data - and, unlike this file,
    // they are not rewritten when the owner switches binding off, so one of
    // them can sit there sealed long after the key is gone. Treating that as a
    // stolen directory would lock the owner out over nothing.
    authorizationConfig = (fileName == "tgnet.dat");
    foreignConfig = false;
    configPath = ConnectionsManager::getInstance(instanceNum).currentConfigPath + fileName;
    backupPath = configPath + ".bak";
    FILE *backup = fopen(backupPath.c_str(), "rb");
    if (backup != nullptr) {
        if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) backup file found %s", this, configPath.c_str(), backupPath.c_str());
        fclose(backup);
        remove(configPath.c_str());
        rename(backupPath.c_str(), configPath.c_str());
    }
}

NativeByteBuffer *Config::readConfig() {
    NativeByteBuffer *buffer = nullptr;
    FILE *file = fopen(configPath.c_str(), "rb");
    if (file != nullptr) {
        fseek(file, 0, SEEK_END);
        long fileSize = ftell(file);
        if (fseek(file, 0, SEEK_SET)) {
            if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) failed fseek to begin, reopen it", this, configPath.c_str());
            fclose(file);
            file = fopen(configPath.c_str(), "rb");
        }
        uint32_t size = 0;
        size_t bytesRead = fread(&size, sizeof(uint32_t), 1, file);
        if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) load, size = %u, fileSize = %u", this, configPath.c_str(), size, (uint32_t) fileSize);
        if (bytesRead > 0 && size > 0 && (int32_t) size < fileSize) {
            std::vector<uint8_t> stored(size);
            if (fread(stored.data(), sizeof(uint8_t), size, file) == size) {
                if (NovaConfigSeal::startsWithMagic(stored.data(), stored.size())) {
                    // NovaGram: sealed to the device that wrote it. A failure
                    // here answers "no config" rather than "empty config" -
                    // there is no authorization to be had from bytes this
                    // device cannot read - and open() records the failure as a
                    // foreign config, which is what stops the caller from
                    // treating "no config" as a clean install and writing a
                    // fresh one over the ciphertext.
                    std::vector<uint8_t> plain;
                    if (NovaConfigSeal::open(stored.data(), stored.size(), plain, authorizationConfig)
                        && !plain.empty()) {
                        buffer = BuffersStorage::getInstance().getFreeBuffer((uint32_t) plain.size());
                        memcpy(buffer->bytes(), plain.data(), plain.size());
                    } else if (authorizationConfig) {
                        foreignConfig = true;
                    }
                } else {
                    // Written before this build, or with binding switched off.
                    buffer = BuffersStorage::getInstance().getFreeBuffer(size);
                    memcpy(buffer->bytes(), stored.data(), size);
                }
            }
        }
        fclose(file);
    }
    return buffer;
}

bool Config::metForeignConfig() const {
    return foreignConfig;
}

// NovaGram: peeks at the first bytes of whatever is on disk now. The layout is
// the one writeConfig produces - a uint32 length, then the payload - so the
// magic, when there is one, sits right after the length.
bool Config::storedConfigIsSealed() {
    const size_t magicSize = NovaConfigSeal::magicSize();
    const size_t headSize = sizeof(uint32_t) + magicSize;
    // Asked of NovaConfigSeal rather than assumed, and checked rather than
    // trusted: a magic that outgrew this buffer would turn every downgrade
    // check below into a silent no-op.
    uint8_t head[sizeof(uint32_t) + 16];
    if (magicSize == 0 || headSize > sizeof(head)) {
        // Cannot look, so must not claim the file is unsealed. Saying "sealed"
        // is the answer that refuses writes rather than the one that allows a
        // downgrade.
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) cannot peek at the seal, treating the stored config as sealed", this, configPath.c_str());
        return true;
    }
    FILE *file = fopen(configPath.c_str(), "rb");
    if (file == nullptr) {
        // A write interrupted earlier leaves the previous contents under the
        // backup name, and the constructor puts them back on the next start.
        // Those bytes are the ones a write would replace, so they count.
        file = fopen(backupPath.c_str(), "rb");
        if (file == nullptr) {
            return false;
        }
    }
    size_t bytesRead = fread(head, sizeof(uint8_t), headSize, file);
    fclose(file);
    if (bytesRead != headSize) {
        return false;
    }
    return NovaConfigSeal::startsWithMagic(head + sizeof(uint32_t), magicSize);
}

void Config::writeConfig(NativeByteBuffer *buffer) {
    if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) start write config", this, configPath.c_str());

    if (NovaConfigSeal::writesForbidden()) {
        // NovaGram: the emergency wipe is running. Nothing more goes to disk
        // in this process - the sweep is about to remove these files and the
        // device key with them, and a write landing in between is exactly the
        // plaintext window that ordering exists to close.
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) config writes are stopped for the wipe", this, configPath.c_str());
        return;
    }

    // NovaGram: what is already on disk decides what this write is allowed to
    // do, not the setting (I14). Two ways a write could destroy the very thing
    // the seal exists for, both refused here.
    const bool storedSealed = storedConfigIsSealed();
    if (storedSealed && (foreignConfig || NovaConfigSeal::metForeignConfig())) {
        // A sealed file this device cannot open was met during the read. It is
        // the evidence that the directory was copied here, and D13 says it is
        // never overwritten automatically: the client blocks and offers the one
        // explicit start over. Overwriting would turn a refusal into a silent
        // new session.
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) refusing to overwrite a config sealed to another device", this, configPath.c_str());
        return;
    }

    // Sealed before a single file is touched. Once the old config has been
    // renamed to the backup there is no good way to change our mind, and
    // giving up on a write leaves readable authorization keys behind - the
    // exact thing this exists to prevent.
    const uint8_t *payload = buffer->bytes();
    uint32_t payloadSize = buffer->position();
    std::vector<uint8_t> sealed;
    if (NovaConfigSeal::sealsWrites()) {
        if (!NovaConfigSeal::seal(buffer->bytes(), payloadSize, sealed)) {
            if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) sealing failed, keeping the previous config", this, configPath.c_str());
            return;
        }
        payload = sealed.data();
        payloadSize = (uint32_t) sealed.size();
    } else if (authorizationConfig && storedSealed && !NovaConfigSeal::hasKey()) {
        // Would put cleartext datacenter authorization keys where a sealed file
        // stands, while holding no key to open what is there. That is not the
        // owner switching binding off - that path keeps the key precisely so
        // the unsealing rewrite can happen (N15) - it is sealing having become
        // unavailable, and the answer to that is to write nothing. Only this
        // file: the others hold public routing data, and refusing to refresh
        // them would cost reconnects and protect nothing.
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) refusing to replace a sealed config with cleartext: no key to open it", this, configPath.c_str());
        return;
    }

    FILE *file = fopen(configPath.c_str(), "rb");
    FILE *backup = fopen(backupPath.c_str(), "rb");
    bool error = false;
    bool hasBackupFile = false;
    // NovaGram: upstream handled the backup only from inside the "config file
    // exists" branch, and never set hasBackupFile when a backup was already on
    // disk. Two silent consequences. The handle leaked whenever the backup was
    // there and the config was not - the state a write interrupted between the
    // rename and the new file leaves behind. And a pre-existing backup then
    // survived a successful write, so the constructor above, which restores
    // any backup it finds, put it back over the fresh config on the next start
    // and rolled the write back. A backup that is already here is a usable
    // one, so it is closed once, recorded, and removed with the others when
    // the write succeeds. The error path is unchanged: it removes the new
    // config and leaves the backup for the constructor to restore.
    if (backup != nullptr) {
        fclose(backup);
        backup = nullptr;
        hasBackupFile = true;
    }
    if (file != nullptr) {
        fclose(file);
        if (hasBackupFile) {
            remove(configPath.c_str());
        } else if (rename(configPath.c_str(), backupPath.c_str()) != 0) {
            if (LOGS_ENABLED) DEBUG_E("Config(%p) unable to rename file %s to backup file %s", this, configPath.c_str(), backupPath.c_str());
            error = true;
        } else {
            hasBackupFile = true;
        }
    }
    if (error) {
        return;
    }
    file = fopen(configPath.c_str(), "wb");
    if (chmod(configPath.c_str(), 0660)) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) chmod failed", this, configPath.c_str());
    }
    if (file == nullptr) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) unable to open file for writing", this, configPath.c_str());
        return;
    }
    uint32_t size = payloadSize;
    if (fwrite(&size, sizeof(uint32_t), 1, file) == 1) {
        if (fwrite(payload, sizeof(uint8_t), size, file) != size) {
            if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) failed to write config data to file", this, configPath.c_str());
            error = true;
        }
    } else {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) failed to write config size to file", this, configPath.c_str());
        error = true;
    }
    if (fflush(file)) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) fflush failed", this, configPath.c_str());
        error = true;
    }
    int fd = fileno(file);
    if (fd == -1) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) fileno failed", this, configPath.c_str());
        error = true;
    } else {
        if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) fileno = %d", this, configPath.c_str(), fd);
    }
    if (fd != -1 && fsync(fd) == -1) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) fsync failed", this, configPath.c_str());
        error = true;
    }
    if (fclose(file)) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) fclose failed", this, configPath.c_str());
        error = true;
    }
    if (error) {
        if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) failed to write config", this, configPath.c_str());
        if (remove(configPath.c_str())) {
            if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) remove config failed", this, configPath.c_str());
        }
    } else {
        if (hasBackupFile && remove(backupPath.c_str())) {
            if (LOGS_ENABLED) DEBUG_E("Config(%p, %s) remove backup failed, %s", this, backupPath.c_str(), strerror(errno));
        }
    }
    if (!error) {
        if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) config write ok", this, configPath.c_str());
    }
}
