// For tests on a computer: Android's log, quiet.
#ifndef STUB_ANDROID_LOG_H
#define STUB_ANDROID_LOG_H

enum { ANDROID_LOG_VERBOSE = 2, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL };

inline int __android_log_print(int, const char*, const char*, ...) { return 0; }

#endif
