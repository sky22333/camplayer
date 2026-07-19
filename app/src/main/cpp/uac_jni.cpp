/**
 * JNI for com.jiangdg.uac.UACAudio — libuac + AUSBC libusb100；16KB page align.
 */

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <libuac.h>

#define LOG_TAG "UACAudio"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct UacSession {
    JavaVM *jvm = nullptr;
    jobject thiz = nullptr; // GlobalRef
    jmethodID pcmDataMethod = nullptr;
    jfieldID nativePtrField = nullptr;

    std::shared_ptr<uac::uac_context> context;
    std::shared_ptr<uac::uac_device_handle> deviceHandle;
    std::shared_ptr<uac::uac_stream_handle> streamHandle;
    std::unique_ptr<const uac::uac_audio_config_uncompressed> config;
    const uac::uac_stream_if *streamIf = nullptr;
    // Cached from selected config (route lifetime is separate)
    int sampleRate = 0;
    int channelCount = 0;
    int bitResolution = 0;
    std::atomic<bool> recording{false};
    std::mutex mutex;
};

UacSession *fromPtr(jlong id) {
    return reinterpret_cast<UacSession *>(static_cast<uintptr_t>(id));
}

jlong toPtr(UacSession *s) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(s));
}

void deliverPcm(UacSession *session, uint8_t *data, unsigned size) {
    if (session == nullptr || data == nullptr || size == 0) return;
    if (!session->recording.load()) return;
    JNIEnv *env = nullptr;
    bool needDetach = false;
    int getEnv = session->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (getEnv == JNI_EDETACHED) {
        if (session->jvm->AttachCurrentThread(&env, nullptr) != 0) {
            return;
        }
        needDetach = true;
    } else if (getEnv != JNI_OK || env == nullptr) {
        return;
    }
    if (!session->recording.load()) {
        if (needDetach) session->jvm->DetachCurrentThread();
        return;
    }

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
    if (arr != nullptr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size), reinterpret_cast<jbyte *>(data));
        env->CallVoidMethod(session->thiz, session->pcmDataMethod, arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(arr);
    }

    if (needDetach) {
        session->jvm->DetachCurrentThread();
    }
}

bool selectConfig(UacSession *session, const uac::uac_stream_if &streamIf) {
    auto rates = streamIf.get_sample_rates(uac::UAC_FORMAT_DATA_PCM);
    auto channels = streamIf.get_channel_counts(uac::UAC_FORMAT_DATA_PCM);

    std::vector<uint32_t> tryRates;
    tryRates.push_back(48000);
    for (auto r : rates) {
        if (r != 48000) tryRates.push_back(r);
    }

    std::vector<uint8_t> tryCh;
    // Prefer 2ch → 1ch, then device list
    tryCh.push_back(2);
    tryCh.push_back(1);
    for (auto c : channels) {
        if (c != 1 && c != 2) tryCh.push_back(c);
    }

    for (auto ch : tryCh) {
        for (auto rate : tryRates) {
            auto cfg = streamIf.query_config_uncompressed(uac::UAC_FORMAT_DATA_PCM, ch, rate);
            if (cfg) {
                session->sampleRate = static_cast<int>(cfg->tSampleRate);
                session->channelCount = cfg->bChannelCount;
                session->bitResolution = cfg->bBitResolution;
                session->config = std::move(cfg);
                session->streamIf = &streamIf;
                ALOGI("selected PCM ch=%d rate=%d bit=%d",
                      session->channelCount, session->sampleRate, session->bitResolution);
                return true;
            }
        }
    }
    return false;
}

bool pickStream(UacSession *session) {
    auto device = session->deviceHandle->get_device();
    const uac::uac_terminal_type inputs[] = {
            uac::UAC_TERMINAL_EXTERNAL_UNDEFINED,
            uac::UAC_TERMINAL_MICROPHONE,
            uac::UAC_TERMINAL_INPUT_UNDEFINED,
            uac::UAC_TERMINAL_ANY,
    };
    for (auto inTerm : inputs) {
        auto routes = device->query_audio_routes(inTerm, uac::UAC_TERMINAL_USB_STREAMING);
        for (auto &routeRef : routes) {
            try {
                auto &streamIf = device->get_stream_interface(routeRef.get());
                if (selectConfig(session, streamIf)) {
                    return true;
                }
            } catch (const std::exception &e) {
                ALOGW("route skip: %s", e.what());
            }
        }
    }
    return false;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_jiangdg_uac_UACAudio_nativeInit(
        JNIEnv *env,
        jobject thiz,
        jint vid,
        jint pid,
        jint busnum,
        jint devaddr,
        jint fd,
        jstring usbfs) {
    const char *usbfsChars = usbfs != nullptr ? env->GetStringUTFChars(usbfs, nullptr) : nullptr;
    std::string usbfsStr = usbfsChars ? usbfsChars : "/dev/bus/usb";
    if (usbfsChars) env->ReleaseStringUTFChars(usbfs, usbfsChars);

    auto *session = new UacSession();
    env->GetJavaVM(&session->jvm);
    session->thiz = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    session->pcmDataMethod = env->GetMethodID(cls, "pcmData", "([B)V");
    session->nativePtrField = env->GetFieldID(cls, "mNativePtr", "J");
    env->DeleteLocalRef(cls);

    if (session->pcmDataMethod == nullptr || session->nativePtrField == nullptr) {
        ALOGE("nativeInit: missing Java members");
        if (session->thiz) env->DeleteGlobalRef(session->thiz);
        delete session;
        return -1;
    }

    try {
        session->context = uac::uac_context::create();
        session->deviceHandle = session->context->wrap_android(
                vid, pid, busnum, devaddr, fd, usbfsStr.c_str());
        if (!pickStream(session)) {
            ALOGE("nativeInit: no usable PCM route");
            throw std::runtime_error("no PCM audio route");
        }
        env->SetLongField(thiz, session->nativePtrField, toPtr(session));
        ALOGI("nativeInit ok vid=%04x pid=%04x fd=%d ptr=%p", vid, pid, fd, session);
        return 0;
    } catch (const std::exception &e) {
        ALOGE("nativeInit failed: %s", e.what());
        if (session->thiz) env->DeleteGlobalRef(session->thiz);
        jfieldID field = session->nativePtrField;
        delete session;
        if (field != nullptr) {
            env->SetLongField(thiz, field, 0);
        }
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jiangdg_uac_UACAudio_nativeRelease(JNIEnv *env, jobject /*thiz*/, jlong id) {
    auto *session = fromPtr(id);
    if (session == nullptr) return;
    std::shared_ptr<uac::uac_stream_handle> handle;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        session->recording = false;
        handle = std::move(session->streamHandle);
        session->streamHandle.reset();
    }
    // stop 在锁外：内部会等 ISO 回调结束，避免与 deliverPcm 交织死锁。
    if (handle) {
        try {
            handle->stop();
        } catch (...) {
        }
    }
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        session->deviceHandle.reset();
        session->context.reset();
    }
    if (session->thiz) {
        env->DeleteGlobalRef(session->thiz);
        session->thiz = nullptr;
    }
    delete session;
    ALOGI("nativeRelease done");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jiangdg_uac_UACAudio_nativeStartRecord(JNIEnv * /*env*/, jobject /*thiz*/, jlong id) {
    auto *session = fromPtr(id);
    if (session == nullptr || session->streamIf == nullptr || !session->config) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->recording) return 0;
    try {
        session->streamHandle = session->deviceHandle->start_streaming(
                *session->streamIf,
                *session->config,
                [session](uint8_t *data, unsigned size) {
                    deliverPcm(session, data, size);
                });
        session->recording = true;
        ALOGI("nativeStartRecord ok");
        return 0;
    } catch (const std::exception &e) {
        ALOGE("nativeStartRecord failed: %s", e.what());
        session->streamHandle.reset();
        session->recording = false;
        return -1;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jiangdg_uac_UACAudio_nativeStopRecord(JNIEnv * /*env*/, jobject /*thiz*/, jlong id) {
    auto *session = fromPtr(id);
    if (session == nullptr) return -1;
    std::shared_ptr<uac::uac_stream_handle> handle;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (!session->recording) {
            return 0;
        }
        session->recording = false;
        handle = std::move(session->streamHandle);
        session->streamHandle.reset();
    }
    try {
        if (handle) {
            handle->stop();
        }
        ALOGI("nativeStopRecord ok");
        return 0;
    } catch (const std::exception &e) {
        ALOGE("nativeStopRecord failed: %s", e.what());
        return -1;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jiangdg_uac_UACAudio_nativeGetRecordingState(JNIEnv *, jobject, jlong id) {
    auto *session = fromPtr(id);
    return session != nullptr && session->recording.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jiangdg_uac_UACAudio_nativeGetSampleRate(JNIEnv *, jobject, jlong id) {
    auto *session = fromPtr(id);
    return session ? session->sampleRate : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jiangdg_uac_UACAudio_nativeGetChannelCount(JNIEnv *, jobject, jlong id) {
    auto *session = fromPtr(id);
    return session ? session->channelCount : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jiangdg_uac_UACAudio_nativeGetBitResolution(JNIEnv *, jobject, jlong id) {
    auto *session = fromPtr(id);
    return session ? session->bitResolution : 0;
}
