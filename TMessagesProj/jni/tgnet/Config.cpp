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
                if (NovaConfigSeal::isSealed(stored.data(), stored.size())) {
                    // NovaGram: sealed to the device that wrote it. A failure
                    // here answers "no config" rather than "empty config" -
                    // there is no authorization to be had from bytes this
                    // device cannot read.
                    std::vector<uint8_t> plain;
                    if (NovaConfigSeal::open(stored.data(), stored.size(), plain)
                        && !plain.empty()) {
                        buffer = BuffersStorage::getInstance().getFreeBuffer((uint32_t) plain.size());
                        memcpy(buffer->bytes(), plain.data(), plain.size());
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

void Config::writeConfig(NativeByteBuffer *buffer) {
    if (LOGS_ENABLED) DEBUG_D("Config(%p, %s) start write config", this, configPath.c_str());

    // NovaGram: sealed before a single file is touched. Once the old config
    // has been renamed to the backup there is no good way to change our mind,
    // and giving up on a write leaves readable authorization keys behind - the
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
    }

    FILE *file = fopen(configPath.c_str(), "rb");
    FILE *backup = fopen(backupPath.c_str(), "rb");
    bool error = false;
    bool hasBackupFile = false;
    if (file != nullptr) {
        if (backup == nullptr) {
            fclose(file);
            if (rename(configPath.c_str(), backupPath.c_str()) != 0) {
                if (LOGS_ENABLED) DEBUG_E("Config(%p) unable to rename file %s to backup file %s", this, configPath.c_str(), backupPath.c_str());
                error = true;
            } else {
                hasBackupFile = true;
            }
        } else {
            fclose(file);
            fclose(backup);
            remove(configPath.c_str());
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
