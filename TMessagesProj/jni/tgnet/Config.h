/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#ifndef CONFIG_H
#define CONFIG_H

#include <string>
#include "NativeByteBuffer.h"

class Config {

public:
    Config(int32_t instance, std::string fileName);

    NativeByteBuffer *readConfig();
    void writeConfig(NativeByteBuffer *buffer);

    // NovaGram: this file was sealed and this device could not open it.
    // Answered per file on purpose. The process-wide flag in NovaConfigSeal is
    // right for "stop writing", but wrong as the answer Java latches into the
    // blocked screen: one account's failed read would then be reported again
    // after the next account's perfectly good one, and the owner would be shown
    // "belongs to another device" with only an irreversible wipe on offer.
    bool metForeignConfig() const;

private:
    // NovaGram: whether the bytes already on disk carry the seal. Asked of the
    // file rather than remembered from the last read, because writeConfig is
    // reached on Config objects whose readConfig was never called (the CDN key
    // store is one) and because it is the state of the disk, not of this
    // process, that decides whether a write would be a downgrade.
    bool storedConfigIsSealed();

    // NovaGram: whether this file is the one holding the datacenter
    // authorization keys. Only that one is evidence of anything.
    bool authorizationConfig;
    bool foreignConfig;

    int32_t instanceNum;
    std::string configPath;
    std::string backupPath;
};

#endif
