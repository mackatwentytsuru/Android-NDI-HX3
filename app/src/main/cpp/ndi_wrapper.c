/**
 * ndi_wrapper.c - Pure C JNI wrapper for the official NDI SDK (v6)
 *
 * IMPORTANT: This is pure C (not C++) to avoid ABI compatibility issues
 * with the NDI SDK on Android.
 *
 * Prerequisites:
 * - NDI SDK headers in app/src/main/cpp/include/
 * - libndi.so in app/src/main/jniLibs/arm64-v8a/
 */

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "Processing.NDI.Lib.h"

/* Logging Macros */
#define LOG_TAG "NdiNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Kotlin FourCC constants for compressed frames. */
static const uint32_t FOURCC_H264 = 0x34363248; /* 'H264' */
static const uint32_t FOURCC_HEVC = 0x43564548; /* 'HEVC' */

/* ============================================================================
 * Global State
 * ========================================================================== */

static pthread_mutex_t g_init_mutex = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_initialized = 0;

static pthread_mutex_t g_jni_cache_mutex = PTHREAD_MUTEX_INITIALIZER;
static int g_jni_cache_initialized = 0;
static jclass g_class_VideoFrame = NULL;
static jmethodID g_ctor_VideoFrame = NULL;
static jclass g_class_AudioFrame = NULL;
static jmethodID g_ctor_AudioFrame = NULL;
static jclass g_class_ReceiverPerformance = NULL;
static jmethodID g_ctor_ReceiverPerformance = NULL;

typedef struct NdiFinderWrapper {
    NDIlib_find_instance_t finder;
    pthread_mutex_t mutex;
} NdiFinderWrapper;

typedef struct NdiReceiverWrapper {
    NDIlib_recv_instance_t recv;
    pthread_mutex_t mutex;
    ANativeWindow* surface_window;
} NdiReceiverWrapper;

typedef struct NdiVideoFrameHandle {
    NDIlib_recv_instance_t recv;
    NDIlib_video_frame_v2_t frame;
} NdiVideoFrameHandle;

typedef struct NdiAudioFrameHandle {
    NDIlib_recv_instance_t recv;
    NDIlib_audio_frame_v2_t frame;
    float* interleaved_data;
    size_t interleaved_bytes;
} NdiAudioFrameHandle;

/* ============================================================================
 * Helper Functions
 * ========================================================================== */

static char* c_strdup(const char* s) {
    if (s == NULL) {
        return NULL;
    }
    const size_t len = strlen(s) + 1;
    char* out = (char*)malloc(len);
    if (out != NULL) {
        memcpy(out, s, len);
    }
    return out;
}

static char* jstring_to_cstring(JNIEnv* env, jstring jstr) {
    if (jstr == NULL) {
        return NULL;
    }
    const char* utf = (*env)->GetStringUTFChars(env, jstr, NULL);
    if (utf == NULL) {
        return NULL;
    }
    char* result = c_strdup(utf);
    (*env)->ReleaseStringUTFChars(env, jstr, utf);
    return result;
}

static jstring cstring_to_jstring(JNIEnv* env, const char* str) {
    if (str == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, str);
}

static bool is_empty_string(const char* s) {
    return (s == NULL) || (s[0] == '\0');
}

static NDIlib_recv_bandwidth_e map_bandwidth(jint bandwidth) {
    switch (bandwidth) {
        case 0: return NDIlib_recv_bandwidth_metadata_only;
        case 1: return NDIlib_recv_bandwidth_audio_only;
        case 2: return NDIlib_recv_bandwidth_lowest;
        case 3: return NDIlib_recv_bandwidth_highest;
        default: return NDIlib_recv_bandwidth_highest;
    }
}

static NDIlib_recv_color_format_e map_color_format(jint colorFormat) {
    switch (colorFormat) {
        case 0: return NDIlib_recv_color_format_BGRX_BGRA;
        case 1: return NDIlib_recv_color_format_UYVY_BGRA;
        case 2: return NDIlib_recv_color_format_RGBX_RGBA;
        case 3: return NDIlib_recv_color_format_UYVY_RGBA;
        case 100: return NDIlib_recv_color_format_fastest;
        case 101: return NDIlib_recv_color_format_best;
        default: return NDIlib_recv_color_format_UYVY_BGRA;
    }
}

static int ensure_jni_cache(JNIEnv* env) {
    if (g_jni_cache_initialized) {
        return 1;
    }

    pthread_mutex_lock(&g_jni_cache_mutex);
    if (g_jni_cache_initialized) {
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 1;
    }

    jclass localVideoFrame = (*env)->FindClass(env, "com/example/ndireceiver/ndi/NdiNative$VideoFrame");
    if (localVideoFrame == NULL) {
        LOGE("Failed to find class NdiNative$VideoFrame");
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 0;
    }
    g_class_VideoFrame = (jclass)(*env)->NewGlobalRef(env, localVideoFrame);
    (*env)->DeleteLocalRef(env, localVideoFrame);
    if (g_class_VideoFrame == NULL) {
        LOGE("Failed to create global ref for NdiNative$VideoFrame");
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 0;
    }
    g_ctor_VideoFrame = (*env)->GetMethodID(
        env,
        g_class_VideoFrame,
        "<init>",
        "(JIIIIIIJLjava/nio/ByteBuffer;Z)V"
    );
    if (g_ctor_VideoFrame == NULL) {
        LOGE("Failed to find VideoFrame constructor");
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 0;
    }

    jclass localAudioFrame = (*env)->FindClass(env, "com/example/ndireceiver/ndi/NdiNative$AudioFrame");
    if (localAudioFrame == NULL) {
        LOGE("Failed to find class NdiNative$AudioFrame");
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 0;
    }
    g_class_AudioFrame = (jclass)(*env)->NewGlobalRef(env, localAudioFrame);
    (*env)->DeleteLocalRef(env, localAudioFrame);
    if (g_class_AudioFrame == NULL) {
        LOGE("Failed to create global ref for NdiNative$AudioFrame");
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 0;
    }
    g_ctor_AudioFrame = (*env)->GetMethodID(env, g_class_AudioFrame, "<init>", "(JIIIJLjava/nio/ByteBuffer;)V");
    if (g_ctor_AudioFrame == NULL) {
        LOGE("Failed to find AudioFrame constructor");
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 0;
    }

    jclass localPerf = (*env)->FindClass(env, "com/example/ndireceiver/ndi/NdiNative$ReceiverPerformance");
    if (localPerf == NULL) {
        LOGE("Failed to find class NdiNative$ReceiverPerformance");
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 0;
    }
    g_class_ReceiverPerformance = (jclass)(*env)->NewGlobalRef(env, localPerf);
    (*env)->DeleteLocalRef(env, localPerf);
    if (g_class_ReceiverPerformance == NULL) {
        LOGE("Failed to create global ref for NdiNative$ReceiverPerformance");
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 0;
    }
    g_ctor_ReceiverPerformance = (*env)->GetMethodID(env, g_class_ReceiverPerformance, "<init>", "(JJJJJI)V");
    if (g_ctor_ReceiverPerformance == NULL) {
        LOGE("Failed to find ReceiverPerformance constructor");
        pthread_mutex_unlock(&g_jni_cache_mutex);
        return 0;
    }

    g_jni_cache_initialized = 1;
    pthread_mutex_unlock(&g_jni_cache_mutex);
    return 1;
}

/* ============================================================================
 * JNI Exports - Library Initialization
 * ========================================================================== */

JNIEXPORT jboolean JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_initialize(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;

    pthread_mutex_lock(&g_init_mutex);

    if (g_initialized) {
        pthread_mutex_unlock(&g_init_mutex);
        LOGW("NDI SDK already initialized");
        return JNI_TRUE;
    }

    LOGI("Initializing NDI SDK...");
    if (!NDIlib_initialize()) {
        pthread_mutex_unlock(&g_init_mutex);
        LOGE("Failed to initialize NDI SDK");
        return JNI_FALSE;
    }

    g_initialized = 1;
    pthread_mutex_unlock(&g_init_mutex);
    LOGI("NDI SDK initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_destroy(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;

    pthread_mutex_lock(&g_init_mutex);

    if (!g_initialized) {
        pthread_mutex_unlock(&g_init_mutex);
        LOGW("NDI SDK not initialized, nothing to destroy");
        return;
    }

    LOGI("Destroying NDI SDK...");
    NDIlib_destroy();
    g_initialized = 0;
    pthread_mutex_unlock(&g_init_mutex);
    LOGI("NDI SDK destroyed");
}

JNIEXPORT jboolean JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_isInitialized(JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_getVersion(JNIEnv* env, jobject thiz) {
    (void)thiz;
    const char* version = NDIlib_version();
    return cstring_to_jstring(env, version ? version : "unknown");
}

/* ============================================================================
 * JNI Exports - NDI Finder (Source Discovery)
 * ========================================================================== */

JNIEXPORT jlong JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_finderCreate(
        JNIEnv* env,
        jobject thiz,
        jboolean showLocalSources,
        jstring groups,
        jstring extraIps) {

    (void)thiz;

    if (!g_initialized) {
        LOGE("finderCreate: NDI SDK not initialized");
        return 0;
    }

    char* groups_str = jstring_to_cstring(env, groups);
    char* extra_ips_str = jstring_to_cstring(env, extraIps);

    LOGD("Creating NDI finder (showLocal=%d, groups='%s', extraIps='%s')",
         showLocalSources,
         groups_str ? groups_str : "",
         extra_ips_str ? extra_ips_str : "");

    NDIlib_find_create_t settings;
    memset(&settings, 0, sizeof(settings));
    settings.show_local_sources = (showLocalSources == JNI_TRUE);
    settings.p_groups = is_empty_string(groups_str) ? NULL : groups_str;
    settings.p_extra_ips = is_empty_string(extra_ips_str) ? NULL : extra_ips_str;

    NDIlib_find_instance_t finder = NDIlib_find_create_v2(&settings);
    free(groups_str);
    free(extra_ips_str);

    if (finder == NULL) {
        LOGE("finderCreate: NDIlib_find_create_v2 failed");
        return 0;
    }

    NdiFinderWrapper* wrapper = (NdiFinderWrapper*)calloc(1, sizeof(NdiFinderWrapper));
    if (wrapper == NULL) {
        LOGE("finderCreate: Out of memory");
        NDIlib_find_destroy(finder);
        return 0;
    }

    wrapper->finder = finder;
    if (pthread_mutex_init(&wrapper->mutex, NULL) != 0) {
        LOGE("finderCreate: pthread_mutex_init failed");
        NDIlib_find_destroy(finder);
        free(wrapper);
        return 0;
    }

    return (jlong)(intptr_t)wrapper;
}

JNIEXPORT void JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_finderDestroy(
        JNIEnv* env,
        jobject thiz,
        jlong finderPtr) {

    (void)env;
    (void)thiz;

    if (finderPtr == 0) {
        return;
    }

    NdiFinderWrapper* wrapper = (NdiFinderWrapper*)(intptr_t)finderPtr;
    if (wrapper == NULL) {
        return;
    }

    LOGD("Destroying NDI finder");
    pthread_mutex_lock(&wrapper->mutex);
    if (wrapper->finder != NULL) {
        NDIlib_find_destroy(wrapper->finder);
        wrapper->finder = NULL;
    }
    pthread_mutex_unlock(&wrapper->mutex);

    pthread_mutex_destroy(&wrapper->mutex);
    free(wrapper);
}

JNIEXPORT jboolean JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_finderWaitForSources(
        JNIEnv* env,
        jobject thiz,
        jlong finderPtr,
        jint timeoutMs) {

    (void)env;
    (void)thiz;

    if (finderPtr == 0) {
        return JNI_FALSE;
    }

    NdiFinderWrapper* wrapper = (NdiFinderWrapper*)(intptr_t)finderPtr;
    if (wrapper == NULL || wrapper->finder == NULL) {
        return JNI_FALSE;
    }

    pthread_mutex_lock(&wrapper->mutex);
    const bool changed = NDIlib_find_wait_for_sources(wrapper->finder, (uint32_t)timeoutMs);
    pthread_mutex_unlock(&wrapper->mutex);
    return changed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_finderGetSources(
        JNIEnv* env,
        jobject thiz,
        jlong finderPtr) {

    (void)thiz;

    if (finderPtr == 0) {
        return NULL;
    }

    NdiFinderWrapper* wrapper = (NdiFinderWrapper*)(intptr_t)finderPtr;
    if (wrapper == NULL || wrapper->finder == NULL) {
        return NULL;
    }

    pthread_mutex_lock(&wrapper->mutex);

    uint32_t no_sources = 0;
    const NDIlib_source_t* sources = NDIlib_find_get_current_sources(wrapper->finder, &no_sources);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (stringClass == NULL) {
        pthread_mutex_unlock(&wrapper->mutex);
        return NULL;
    }

    jobjectArray result = (*env)->NewObjectArray(env, (jsize)no_sources, stringClass, NULL);
    if (result == NULL) {
        pthread_mutex_unlock(&wrapper->mutex);
        return NULL;
    }

    for (uint32_t i = 0; i < no_sources; i++) {
        const char* name_c = (sources != NULL) ? sources[i].p_ndi_name : NULL;
        jstring name = cstring_to_jstring(env, name_c ? name_c : "");
        if (name != NULL) {
            (*env)->SetObjectArrayElement(env, result, (jsize)i, name);
            (*env)->DeleteLocalRef(env, name);
        }
    }

    pthread_mutex_unlock(&wrapper->mutex);
    return result;
}

/* ============================================================================
 * JNI Exports - NDI Receiver
 * ========================================================================== */

JNIEXPORT jlong JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverCreate(
        JNIEnv* env,
        jobject thiz,
        jstring receiverName,
        jint bandwidth,
        jint colorFormat,
        jboolean allowVideoFields) {

    (void)thiz;

    if (!g_initialized) {
        LOGE("receiverCreate: NDI SDK not initialized");
        return 0;
    }

    char* name_str = jstring_to_cstring(env, receiverName);
    LOGD("Creating NDI receiver '%s' (bandwidth=%d, colorFormat=%d, allowFields=%d)",
         name_str ? name_str : "",
         bandwidth,
         colorFormat,
         allowVideoFields);

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)calloc(1, sizeof(NdiReceiverWrapper));
    if (wrapper == NULL) {
        free(name_str);
        LOGE("receiverCreate: Out of memory");
        return 0;
    }
    if (pthread_mutex_init(&wrapper->mutex, NULL) != 0) {
        free(name_str);
        free(wrapper);
        LOGE("receiverCreate: pthread_mutex_init failed");
        return 0;
    }

    NDIlib_recv_create_v3_t settings;
    memset(&settings, 0, sizeof(settings));
    settings.source_to_connect_to.p_ndi_name = NULL;
    settings.source_to_connect_to.p_url_address = NULL;
    settings.color_format = map_color_format(colorFormat);
    settings.bandwidth = map_bandwidth(bandwidth);
    settings.allow_video_fields = (allowVideoFields == JNI_TRUE);
    settings.p_ndi_recv_name = is_empty_string(name_str) ? NULL : name_str;

    wrapper->recv = NDIlib_recv_create_v3(&settings);
    wrapper->surface_window = NULL;

    free(name_str);

    if (wrapper->recv == NULL) {
        LOGE("receiverCreate: NDIlib_recv_create_v3 failed");
        pthread_mutex_destroy(&wrapper->mutex);
        free(wrapper);
        return 0;
    }

    return (jlong)(intptr_t)wrapper;
}

JNIEXPORT void JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverDestroy(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr) {

    (void)env;
    (void)thiz;

    if (receiverPtr == 0) {
        return;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper == NULL) {
        return;
    }

    LOGD("Destroying NDI receiver");

    pthread_mutex_lock(&wrapper->mutex);
    if (wrapper->surface_window != NULL) {
        ANativeWindow_release(wrapper->surface_window);
        wrapper->surface_window = NULL;
    }
    if (wrapper->recv != NULL) {
        NDIlib_recv_destroy(wrapper->recv);
        wrapper->recv = NULL;
    }
    pthread_mutex_unlock(&wrapper->mutex);

    pthread_mutex_destroy(&wrapper->mutex);
    free(wrapper);
}

JNIEXPORT jboolean JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverConnect(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr,
        jstring sourceName) {

    (void)thiz;

    if (receiverPtr == 0) {
        LOGE("receiverConnect: Invalid receiver pointer");
        return JNI_FALSE;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper == NULL || wrapper->recv == NULL) {
        LOGE("receiverConnect: Receiver not available");
        return JNI_FALSE;
    }

    char* source_str = jstring_to_cstring(env, sourceName);
    if (is_empty_string(source_str)) {
        free(source_str);
        LOGE("receiverConnect: sourceName is empty");
        return JNI_FALSE;
    }

    LOGD("Connecting to NDI source: %s", source_str);

    NDIlib_source_t source;
    memset(&source, 0, sizeof(source));
    source.p_ndi_name = source_str;
    source.p_url_address = NULL;

    pthread_mutex_lock(&wrapper->mutex);
    NDIlib_recv_connect(wrapper->recv, &source);
    pthread_mutex_unlock(&wrapper->mutex);

    free(source_str);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverDisconnect(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr) {

    (void)env;
    (void)thiz;

    if (receiverPtr == 0) {
        return;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper == NULL || wrapper->recv == NULL) {
        return;
    }

    LOGD("Disconnecting NDI receiver");
    pthread_mutex_lock(&wrapper->mutex);
    NDIlib_recv_connect(wrapper->recv, NULL);
    pthread_mutex_unlock(&wrapper->mutex);
}

JNIEXPORT jobject JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverCaptureVideo(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr,
        jint timeoutMs) {

    (void)thiz;

    if (receiverPtr == 0) {
        return NULL;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper == NULL || wrapper->recv == NULL) {
        return NULL;
    }

    if (!ensure_jni_cache(env)) {
        return NULL;
    }

    NdiVideoFrameHandle* handle = (NdiVideoFrameHandle*)calloc(1, sizeof(NdiVideoFrameHandle));
    if (handle == NULL) {
        LOGE("receiverCaptureVideo: Out of memory");
        return NULL;
    }
    handle->recv = wrapper->recv;
    memset(&handle->frame, 0, sizeof(handle->frame));

    pthread_mutex_lock(&wrapper->mutex);
    const NDIlib_frame_type_e frame_type = NDIlib_recv_capture_v2(
        wrapper->recv,
        &handle->frame,
        NULL,
        NULL,
        (uint32_t)timeoutMs
    );
    pthread_mutex_unlock(&wrapper->mutex);

    if (frame_type != NDIlib_frame_type_video) {
        free(handle);
        return NULL;
    }

    const uint32_t fourcc = (uint32_t)handle->frame.FourCC;
    const bool is_compressed = (fourcc == FOURCC_H264) || (fourcc == FOURCC_HEVC);

    if (handle->frame.p_data == NULL) {
        LOGW("receiverCaptureVideo: Video frame had NULL p_data");
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_video_v2(wrapper->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
        free(handle);
        return NULL;
    }

    jlong buffer_size = 0;
    if (is_compressed) {
        buffer_size = (jlong)handle->frame.data_size_in_bytes;
    } else {
        const jlong stride = (jlong)handle->frame.line_stride_in_bytes;
        const jlong abs_stride = (stride < 0) ? -stride : stride;
        buffer_size = abs_stride * (jlong)handle->frame.yres;
    }

    if (buffer_size <= 0) {
        LOGW("receiverCaptureVideo: Invalid buffer size (fourcc=0x%08x size=%" PRId64 ")",
             fourcc,
             (int64_t)buffer_size);
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_video_v2(wrapper->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
        free(handle);
        return NULL;
    }

    jobject byteBuffer = (*env)->NewDirectByteBuffer(env, handle->frame.p_data, buffer_size);
    if (byteBuffer == NULL) {
        LOGE("receiverCaptureVideo: NewDirectByteBuffer failed");
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_video_v2(wrapper->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
        free(handle);
        return NULL;
    }

    const jboolean is_progressive =
        (handle->frame.frame_format_type == NDIlib_frame_format_type_progressive) ? JNI_TRUE : JNI_FALSE;
    const jint out_stride = is_compressed ? 0 : (jint)handle->frame.line_stride_in_bytes;

    jobject videoObj = (*env)->NewObject(
        env,
        g_class_VideoFrame,
        g_ctor_VideoFrame,
        (jlong)(intptr_t)handle,
        (jint)handle->frame.xres,
        (jint)handle->frame.yres,
        out_stride,
        (jint)handle->frame.frame_rate_N,
        (jint)handle->frame.frame_rate_D,
        (jint)fourcc,
        (jlong)handle->frame.timestamp,
        byteBuffer,
        is_progressive
    );

    if (videoObj == NULL) {
        LOGE("receiverCaptureVideo: Failed to create VideoFrame object");
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_video_v2(wrapper->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
        free(handle);
        return NULL;
    }

    return videoObj;
}

JNIEXPORT void JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverFreeVideo(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr,
        jlong framePtr) {

    (void)env;
    (void)thiz;

    if (framePtr == 0) {
        return;
    }

    NdiVideoFrameHandle* handle = (NdiVideoFrameHandle*)(intptr_t)framePtr;
    if (handle == NULL) {
        return;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper != NULL) {
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_video_v2(handle->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
    } else {
        NDIlib_recv_free_video_v2(handle->recv, &handle->frame);
    }

    free(handle);
}

JNIEXPORT jobject JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverCaptureAudio(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr,
        jint timeoutMs) {

    (void)thiz;

    if (receiverPtr == 0) {
        return NULL;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper == NULL || wrapper->recv == NULL) {
        return NULL;
    }

    if (!ensure_jni_cache(env)) {
        return NULL;
    }

    NdiAudioFrameHandle* handle = (NdiAudioFrameHandle*)calloc(1, sizeof(NdiAudioFrameHandle));
    if (handle == NULL) {
        LOGE("receiverCaptureAudio: Out of memory");
        return NULL;
    }
    handle->recv = wrapper->recv;
    memset(&handle->frame, 0, sizeof(handle->frame));

    pthread_mutex_lock(&wrapper->mutex);
    const NDIlib_frame_type_e frame_type = NDIlib_recv_capture_v2(
        wrapper->recv,
        NULL,
        &handle->frame,
        NULL,
        (uint32_t)timeoutMs
    );
    pthread_mutex_unlock(&wrapper->mutex);

    if (frame_type != NDIlib_frame_type_audio) {
        free(handle);
        return NULL;
    }

    const int sample_rate = handle->frame.sample_rate;
    const int channels = handle->frame.no_channels;
    const int samples_per_channel = handle->frame.no_samples;

    if (handle->frame.p_data == NULL || sample_rate <= 0 || channels <= 0 || samples_per_channel <= 0) {
        LOGW("receiverCaptureAudio: Invalid audio frame (p_data=%p sr=%d ch=%d samples=%d)",
             (void*)handle->frame.p_data,
             sample_rate,
             channels,
             samples_per_channel);
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_audio_v2(wrapper->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
        free(handle);
        return NULL;
    }

    const size_t total_samples = (size_t)channels * (size_t)samples_per_channel;
    const size_t bytes = total_samples * sizeof(float);

    handle->interleaved_data = (float*)malloc(bytes);
    handle->interleaved_bytes = bytes;
    if (handle->interleaved_data == NULL) {
        LOGE("receiverCaptureAudio: Out of memory for interleaved buffer (%zu bytes)", bytes);
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_audio_v2(wrapper->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
        free(handle);
        return NULL;
    }

    /* Convert planar float audio to interleaved float audio. */
    const uint8_t* base = (const uint8_t*)handle->frame.p_data;
    for (int s = 0; s < samples_per_channel; s++) {
        for (int c = 0; c < channels; c++) {
            const float* channel_ptr = (const float*)(base + ((size_t)c * (size_t)handle->frame.channel_stride_in_bytes));
            handle->interleaved_data[((size_t)s * (size_t)channels) + (size_t)c] = channel_ptr[s];
        }
    }

    jobject byteBuffer = (*env)->NewDirectByteBuffer(env, handle->interleaved_data, (jlong)handle->interleaved_bytes);
    if (byteBuffer == NULL) {
        LOGE("receiverCaptureAudio: NewDirectByteBuffer failed");
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_audio_v2(wrapper->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
        free(handle->interleaved_data);
        free(handle);
        return NULL;
    }

    jobject audioObj = (*env)->NewObject(
        env,
        g_class_AudioFrame,
        g_ctor_AudioFrame,
        (jlong)(intptr_t)handle,
        (jint)sample_rate,
        (jint)channels,
        (jint)samples_per_channel,
        (jlong)handle->frame.timestamp,
        byteBuffer
    );

    if (audioObj == NULL) {
        LOGE("receiverCaptureAudio: Failed to create AudioFrame object");
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_audio_v2(wrapper->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
        free(handle->interleaved_data);
        free(handle);
        return NULL;
    }

    return audioObj;
}

JNIEXPORT void JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverFreeAudio(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr,
        jlong framePtr) {

    (void)env;
    (void)thiz;

    if (framePtr == 0) {
        return;
    }

    NdiAudioFrameHandle* handle = (NdiAudioFrameHandle*)(intptr_t)framePtr;
    if (handle == NULL) {
        return;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper != NULL) {
        pthread_mutex_lock(&wrapper->mutex);
        NDIlib_recv_free_audio_v2(handle->recv, &handle->frame);
        pthread_mutex_unlock(&wrapper->mutex);
    } else {
        NDIlib_recv_free_audio_v2(handle->recv, &handle->frame);
    }

    free(handle->interleaved_data);
    free(handle);
}

JNIEXPORT jobject JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverGetPerformance(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr) {

    (void)thiz;

    if (receiverPtr == 0) {
        return NULL;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper == NULL || wrapper->recv == NULL) {
        return NULL;
    }

    if (!ensure_jni_cache(env)) {
        return NULL;
    }

    NDIlib_recv_performance_t total;
    NDIlib_recv_performance_t dropped;
    memset(&total, 0, sizeof(total));
    memset(&dropped, 0, sizeof(dropped));

    pthread_mutex_lock(&wrapper->mutex);
    NDIlib_recv_get_performance(wrapper->recv, &total, &dropped);
    const int connections = NDIlib_recv_get_no_connections(wrapper->recv);
    pthread_mutex_unlock(&wrapper->mutex);

    int quality = 0;
    if (connections > 0) {
        if (total.video_frames > 0) {
            const double drop_rate = (double)dropped.video_frames / (double)total.video_frames;
            int q = (int)(100.0 - (drop_rate * 100.0));
            if (q < 0) q = 0;
            if (q > 100) q = 100;
            quality = q;
        } else {
            quality = 100;
        }
    }

    return (*env)->NewObject(
        env,
        g_class_ReceiverPerformance,
        g_ctor_ReceiverPerformance,
        (jlong)total.video_frames,
        (jlong)dropped.video_frames,
        (jlong)total.audio_frames,
        (jlong)dropped.audio_frames,
        (jlong)total.metadata_frames,
        (jint)quality
    );
}

JNIEXPORT jboolean JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverIsConnected(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr) {

    (void)env;
    (void)thiz;

    if (receiverPtr == 0) {
        return JNI_FALSE;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper == NULL || wrapper->recv == NULL) {
        return JNI_FALSE;
    }

    pthread_mutex_lock(&wrapper->mutex);
    const int connections = NDIlib_recv_get_no_connections(wrapper->recv);
    pthread_mutex_unlock(&wrapper->mutex);
    return (connections > 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_ndireceiver_ndi_NdiNative_receiverSetSurface(
        JNIEnv* env,
        jobject thiz,
        jlong receiverPtr,
        jobject surface) {

    (void)thiz;

    if (receiverPtr == 0) {
        return JNI_FALSE;
    }

    NdiReceiverWrapper* wrapper = (NdiReceiverWrapper*)(intptr_t)receiverPtr;
    if (wrapper == NULL) {
        return JNI_FALSE;
    }

    pthread_mutex_lock(&wrapper->mutex);

    if (surface == NULL) {
        LOGD("Clearing surface (used by app-side MediaCodec decoder)");
        if (wrapper->surface_window != NULL) {
            ANativeWindow_release(wrapper->surface_window);
            wrapper->surface_window = NULL;
        }
        pthread_mutex_unlock(&wrapper->mutex);
        return JNI_TRUE;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) {
        pthread_mutex_unlock(&wrapper->mutex);
        LOGE("Failed to get ANativeWindow from Surface");
        return JNI_FALSE;
    }

    if (wrapper->surface_window != NULL) {
        ANativeWindow_release(wrapper->surface_window);
    }
    wrapper->surface_window = window;

    LOGD("Surface set (ANativeWindow=%p)", (void*)window);
    pthread_mutex_unlock(&wrapper->mutex);
    return JNI_TRUE;
}

