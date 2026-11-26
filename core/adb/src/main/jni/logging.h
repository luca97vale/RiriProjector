// RiriProjector: minimal logcat macros for adb_pairing.cpp (Shizuku's logging.h is not copied).
#ifndef RIRI_ADB_LOGGING_H
#define RIRI_ADB_LOGGING_H

#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG "Riri/AdbPairing"
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#endif // RIRI_ADB_LOGGING_H
