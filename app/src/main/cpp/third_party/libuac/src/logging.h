// Copyright 2023 Jakub Księżniak
// Simplified logging macros for Android NDK (avoid basename()).
//
// Licensed under the Apache License, Version 2.0

#pragma once

#include <cstdio>
#include <cstring>
#include <config.h>

#ifdef __ANDROID__
#include <android/log.h>

#ifdef UAC_ENABLE_LOGGING
#define LOG_DEBUG(format, ...) __android_log_print(ANDROID_LOG_DEBUG, "UAC", format, ##__VA_ARGS__)
#define LOG_WARN(format, ...) __android_log_print(ANDROID_LOG_WARN, "UAC", format, ##__VA_ARGS__)
#define LOG_ENTER()
#define LOG_EXIT(code)
#define LOG_EXIT_VOID()
#else
#define LOG_DEBUG(...)
#define LOG_WARN(...)
#define LOG_ENTER()
#define LOG_EXIT(code)
#define LOG_EXIT_VOID()
#endif

#ifdef THROW_ON_ERROR
#include "uac_exceptions.h"
#define LOG_ERROR(format, ...) throw uac::uac_exception(format, ##__VA_ARGS__)
#else
#define LOG_ERROR(format, ...) __android_log_print(ANDROID_LOG_ERROR, "UAC", format, ##__VA_ARGS__)
#endif

#else

#define LOG_DEBUG(format, ...) fprintf(stderr, format "\n", ##__VA_ARGS__)
#define LOG_WARN(format, ...) fprintf(stderr, format "\n", ##__VA_ARGS__)
#define LOG_ENTER()
#define LOG_EXIT(code)
#define LOG_EXIT_VOID()

#ifdef THROW_ON_ERROR
#include "uac_exceptions.h"
#define LOG_ERROR(format, ...) throw uac::uac_exception(format, ##__VA_ARGS__)
#else
#define LOG_ERROR(format, ...) fprintf(stderr, format "\n", ##__VA_ARGS__)
#endif

#endif

#ifdef VERBOSE_VLOG
#define LOG_VERBOSE(...) LOG_DEBUG(__VA_ARGS__)
#else
#define LOG_VERBOSE(...)
#endif
