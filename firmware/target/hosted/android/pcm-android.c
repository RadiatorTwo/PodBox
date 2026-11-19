/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 *
 * Copyright (c) 2010 Thomas Martitz
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY
 * KIND, either express or implied.
 *
 ****************************************************************************/

/*
 * PodBox: PCM output through AAudio instead of a Java AudioTrack.
 *
 * A native audio thread pulls the PCM data from Rockbox and writes it to an
 * AAudio stream as 32-bit integer samples, at whatever rate Rockbox asks for.
 * Java (RockboxPCM) is only left with the Android volume and the foreground
 * service. Reasons:
 *  - the Java AudioTrack is limited to 192 kHz on Android 12, AAudio is not
 *  - 32-bit output to the direct (bit-perfect) headphone path of the F20 Pro+
 *  - no more copying every sample through JNI
 *
 * AAudio exists from API 26 on, the native code is built for API 21, so the
 * functions are looked up with dlopen()/dlsym().
 */

#include <jni.h>
#include <stdbool.h>
#define _SYSTEM_WITH_JNI /* for getJavaEnvironment */
#include <pthread.h>
#include <dlfcn.h>
#include <sys/resource.h>
#include <unistd.h>
#include <string.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#include "config.h"
#include "system.h"
#include "kernel.h"
#include "debug.h"
#include "pcm.h"
#include "pcm-internal.h"
#include "pcm_sampr.h"
#include "pcm_sink.h"
#include "audiohw.h"

#ifndef HAVE_PCM_32BIT_PATH
#error "pcm-android.c expects int32 frames from the mixer (HAVE_PCM_32BIT_PATH)"
#endif

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "PodBoxAudio", __VA_ARGS__)

extern JNIEnv *env_ptr;

/* ---- AAudio, loaded at runtime ---- */
static struct
{
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder **);
    void (*setSampleRate)(AAudioStreamBuilder *, int32_t);
    void (*setChannelCount)(AAudioStreamBuilder *, int32_t);
    void (*setFormat)(AAudioStreamBuilder *, aaudio_format_t);
    void (*setPerformanceMode)(AAudioStreamBuilder *, aaudio_performance_mode_t);
    void (*setSharingMode)(AAudioStreamBuilder *, aaudio_sharing_mode_t);
    void (*setUsage)(AAudioStreamBuilder *, aaudio_usage_t);
    void (*setContentType)(AAudioStreamBuilder *, aaudio_content_type_t);
    void (*setBufferCapacityInFrames)(AAudioStreamBuilder *, int32_t);
    aaudio_result_t (*openStream)(AAudioStreamBuilder *, AAudioStream **);
    aaudio_result_t (*deleteBuilder)(AAudioStreamBuilder *);
    aaudio_result_t (*requestStart)(AAudioStream *);
    aaudio_result_t (*requestPause)(AAudioStream *);
    aaudio_result_t (*requestFlush)(AAudioStream *);
    aaudio_result_t (*requestStop)(AAudioStream *);
    aaudio_result_t (*close)(AAudioStream *);
    aaudio_result_t (*write)(AAudioStream *, const void *, int32_t, int64_t);
    aaudio_result_t (*waitForStateChange)(AAudioStream *, aaudio_stream_state_t,
                                          aaudio_stream_state_t *, int64_t);
    int32_t (*getSampleRate)(AAudioStream *);
    aaudio_format_t (*getFormat)(AAudioStream *);
    int32_t (*getBufferCapacityInFrames)(AAudioStream *);
    aaudio_result_t (*setBufferSizeInFrames)(AAudioStream *, int32_t);
    int32_t (*getFramesPerBurst)(AAudioStream *);
    int32_t (*getXRunCount)(AAudioStream *);
    const char *(*resultToText)(aaudio_result_t);
} aa;

static bool load_aaudio(void)
{
    void *lib = dlopen("libaaudio.so", RTLD_NOW);
    if (!lib)
    {
        LOG("dlopen libaaudio.so failed: %s", dlerror());
        return false;
    }
#define SYM(field, name) \
    if (!(*(void **)&aa.field = dlsym(lib, name))) \
    { LOG("missing %s", name); return false; }
    SYM(createStreamBuilder,       "AAudio_createStreamBuilder")
    SYM(setSampleRate,             "AAudioStreamBuilder_setSampleRate")
    SYM(setChannelCount,           "AAudioStreamBuilder_setChannelCount")
    SYM(setFormat,                 "AAudioStreamBuilder_setFormat")
    SYM(setPerformanceMode,        "AAudioStreamBuilder_setPerformanceMode")
    SYM(setSharingMode,            "AAudioStreamBuilder_setSharingMode")
    SYM(setUsage,                  "AAudioStreamBuilder_setUsage")
    SYM(setContentType,            "AAudioStreamBuilder_setContentType")
    SYM(setBufferCapacityInFrames, "AAudioStreamBuilder_setBufferCapacityInFrames")
    SYM(openStream,                "AAudioStreamBuilder_openStream")
    SYM(deleteBuilder,             "AAudioStreamBuilder_delete")
    SYM(requestStart,              "AAudioStream_requestStart")
    SYM(requestPause,              "AAudioStream_requestPause")
    SYM(requestFlush,              "AAudioStream_requestFlush")
    SYM(requestStop,               "AAudioStream_requestStop")
    SYM(close,                     "AAudioStream_close")
    SYM(write,                     "AAudioStream_write")
    SYM(waitForStateChange,        "AAudioStream_waitForStateChange")
    SYM(getSampleRate,             "AAudioStream_getSampleRate")
    SYM(getFormat,                 "AAudioStream_getFormat")
    SYM(getBufferCapacityInFrames, "AAudioStream_getBufferCapacityInFrames")
    SYM(setBufferSizeInFrames,     "AAudioStream_setBufferSizeInFrames")
    SYM(getFramesPerBurst,         "AAudioStream_getFramesPerBurst")
    SYM(getXRunCount,              "AAudioStream_getXRunCount")
    SYM(resultToText,              "AAudio_convertResultToText")
#undef SYM
    return true;
}

/* ---- state ---- */

/* Rockbox's PCM data, protected by audio_lock_mutex (sink_lock()) */
static const void *pcm_data_start;
static size_t  pcm_data_size;
static int     audio_locked = 0;
static pthread_mutex_t audio_lock_mutex = PTHREAD_MUTEX_INITIALIZER;

/* the AAudio stream, protected by stream_mutex. Lock order: audio lock
 * first, then stream_mutex; the audio thread never holds stream_mutex
 * while it takes the audio lock. */
static pthread_mutex_t stream_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t  play_cond = PTHREAD_COND_INITIALIZER;
static AAudioStream   *stream;
static aaudio_format_t stream_format;
static int32_t         stream_rate = SAMPR_44;
static volatile bool   playing;
static bool            have_aaudio;

/* fine attenuation from RockboxPCM.set_volume(): 1.0 = untouched samples */
static volatile float  pcm_gain = 1.0f;

/* one write: 50 ms, sized for the highest rate */
#define WRITE_MS          50
#define MAX_RATE          384000
#define MAX_WRITE_FRAMES  (MAX_RATE * WRITE_MS / 1000)
static int32_t out_buf[MAX_WRITE_FRAMES * 2];

static JavaVM   *jvm;
static jclass    RockboxPCM_class;
static jobject   RockboxPCM_instance;
static jmethodID set_volume_method;
static jmethodID play_state_method;

static inline void lock_audio(void)
{
    pthread_mutex_lock(&audio_lock_mutex);
}

static inline void unlock_audio(void)
{
    pthread_mutex_unlock(&audio_lock_mutex);
}

static void sink_lock(void)
{
    if (++audio_locked == 1)
        lock_audio();
}

static void sink_unlock(void)
{
    if (--audio_locked == 0)
        unlock_audio();
}

/* tell Java about play/stop (foreground service, widgets) */
static void notify_play_state(bool play)
{
    JNIEnv *env = getJavaEnvironment();
    if (env && RockboxPCM_instance)
        (*env)->CallVoidMethod(env, RockboxPCM_instance, play_state_method,
                               (jboolean)play);
}

/* ---- stream handling, call with stream_mutex held ---- */

static void close_stream(void)
{
    if (!stream)
        return;
    aa.requestStop(stream);
    aa.close(stream);
    stream = NULL;
}

static bool open_stream(void)
{
    if (stream)
        return true;
    if (!have_aaudio)
        return false;

    /* 32-bit integer if possible (API 31), float as a fallback; Rockbox
     * hands over int32 frames (HAVE_PCM_32BIT_PATH) */
    static const aaudio_format_t formats[] =
        { AAUDIO_FORMAT_PCM_I32, AAUDIO_FORMAT_PCM_FLOAT };

    for (unsigned i = 0; i < ARRAYLEN(formats); i++)
    {
        AAudioStreamBuilder *b;
        if (aa.createStreamBuilder(&b) != AAUDIO_OK)
            return false;
        aa.setSampleRate(b, stream_rate);
        aa.setChannelCount(b, 2);
        aa.setFormat(b, formats[i]);
        aa.setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        /* speaker: deep buffer output instead of the low latency one */
        aa.setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
        aa.setUsage(b, AAUDIO_USAGE_MEDIA);
        aa.setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
        aa.setBufferCapacityInFrames(b, stream_rate / 5); /* 200 ms */
        aaudio_result_t r = aa.openStream(b, &stream);
        aa.deleteBuilder(b);
        if (r != AAUDIO_OK)
        {
            LOG("open %d Hz format %d failed: %s", (int)stream_rate,
                (int)formats[i], aa.resultToText(r));
            stream = NULL;
            continue;
        }
        if (aa.getSampleRate(stream) != stream_rate)
        {
            LOG("got %d Hz instead of %d Hz", (int)aa.getSampleRate(stream),
                (int)stream_rate);
        }
        stream_format = aa.getFormat(stream);
        int32_t cap = aa.getBufferCapacityInFrames(stream);
        aa.setBufferSizeInFrames(stream, cap);
        LOG("stream open: %d Hz, format %d, buffer %d frames, burst %d",
            (int)aa.getSampleRate(stream), (int)stream_format, (int)cap,
            (int)aa.getFramesPerBurst(stream));
        return true;
    }
    return false;
}

static void pause_and_flush(void)
{
    if (!stream)
        return;
    aaudio_stream_state_t st;
    aa.requestPause(stream);
    aa.waitForStateChange(stream, AAUDIO_STREAM_STATE_PAUSING, &st,
                          200 * 1000000LL);
    aa.requestFlush(stream);
}

/* ---- the audio thread ---- */

/* converts the mixer's int32 frames (full scale 2^31) to the stream format */
static void convert(int32_t *dst, const int32_t *src, size_t samples)
{
    float g = pcm_gain;
    if (stream_format == AAUDIO_FORMAT_PCM_FLOAT)
    {
        float *f = (float *)dst;
        float scale = g / 2147483648.0f;
        while (samples--)
            *f++ = *src++ * scale;
    }
    else if (g >= 1.0f)
    {
        memcpy(dst, src, samples * sizeof (int32_t)); /* bit-exact */
    }
    else
    {
        int64_t q = (int64_t)(g * (float)(1 << 30)); /* 2.30 fixed point */
        while (samples--)
            *dst++ = (int32_t)((*src++ * q) >> 30);
    }
}

/* takes up to max_frames from Rockbox; returns the number of frames */
static size_t fill(size_t max_frames)
{
    const size_t frame_bytes = 2 * sizeof(int32_t); /* see HAVE_PCM_32BIT_PATH */
    size_t left = max_frames * frame_bytes;
    int32_t *dst = out_buf;
    bool new_buffer = false;

    lock_audio();

    if (!pcm_data_size) /* get some initial data */
        new_buffer = pcm_play_dma_complete_callback(PCM_DMAST_OK,
                            &pcm_data_start, &pcm_data_size);

    while (left > 0 && pcm_data_size)
    {
        size_t n = MIN(left, pcm_data_size);
        convert(dst, pcm_data_start, n / sizeof(int32_t));
        dst += n / sizeof(int32_t);
        left -= n;
        pcm_data_size -= n;
        pcm_data_start += n;

        if (new_buffer)
        {
            new_buffer = false;
            pcm_play_dma_status_callback(PCM_DMAST_STARTED);
        }

        if (!pcm_is_playing())
            break;

        if (pcm_data_size == 0) /* need new data */
            new_buffer = pcm_play_dma_complete_callback(PCM_DMAST_OK,
                                &pcm_data_start, &pcm_data_size);
    }

    if (new_buffer)
        pcm_play_dma_status_callback(PCM_DMAST_STARTED);

    unlock_audio();
    return max_frames - left / frame_bytes;
}

/* reopen after the output went away (headphones unplugged:
 * AAUDIO_ERROR_DISCONNECTED) or stopped taking data. Call with stream_mutex
 * held. */
static void restart_stream(const char *why)
{
    LOG("restarting stream: %s", why);
    close_stream();
    if (open_stream())
        aa.requestStart(stream);
}

/* writes out_buf completely, or gives up (the data is dropped, so Rockbox's
 * playback never stalls on a broken output). Call with stream_mutex held. */
static void write_all(size_t frames)
{
    const int32_t *p = out_buf;
    int stalls = 0;
    while (frames && playing && stream)
    {
        aaudio_result_t r = aa.write(stream, p, frames, 200 * 1000000LL);
        if (r < 0)
        {
            restart_stream(aa.resultToText(r));
            return;
        }
        if (r == 0)
        {
            /* the output takes nothing for 3 x 200 ms */
            if (++stalls >= 3)
            {
                restart_stream("output stalled");
                return;
            }
            continue;
        }
        stalls = 0;
        p += r * 2;
        frames -= r;
    }
}

static void *audio_thread(void *arg)
{
    (void)arg;
    JNIEnv *env;
    (*jvm)->AttachCurrentThread(jvm, &env, NULL); /* for notify_play_state */
    setpriority(PRIO_PROCESS, gettid(), -19); /* ANDROID_PRIORITY_URGENT_AUDIO */

    while (1)
    {
        pthread_mutex_lock(&stream_mutex);
        while (!playing)
            pthread_cond_wait(&play_cond, &stream_mutex);
        int32_t frames = stream_rate * WRITE_MS / 1000;
        pthread_mutex_unlock(&stream_mutex);

        size_t got = fill(frames);
        if (!got)
        {
            usleep(2000); /* no data yet; Rockbox stops us if it's the end */
            continue;
        }

        pthread_mutex_lock(&stream_mutex);
        write_all(got);
        pthread_mutex_unlock(&stream_mutex);
    }
    return NULL;
}

/* ---- pcm sink ---- */

static void sink_set_freq(uint16_t freq)
{
    /* called from pcm_apply_settings() while playback is stopped */
    int32_t rate = hw_freq_sampr[freq];
    pthread_mutex_lock(&stream_mutex);
    if (rate != stream_rate)
    {
        LOG("output rate %d -> %d", (int)stream_rate, (int)rate);
        close_stream();
        stream_rate = rate;
        open_stream();
    }
    pthread_mutex_unlock(&stream_mutex);
}

static void sink_dma_start(const void *addr, size_t size)
{
    pcm_data_start = addr;
    pcm_data_size = size;

    pthread_mutex_lock(&stream_mutex);
    if (open_stream())
    {
        aa.requestStart(stream);
        playing = true;
        pthread_cond_signal(&play_cond);
    }
    pthread_mutex_unlock(&stream_mutex);

    notify_play_state(true);
}

static void sink_dma_stop(void)
{
    /* may be called from the audio thread (inside fill()) */
    pthread_mutex_lock(&stream_mutex);
    playing = false;
    pause_and_flush();
    pthread_mutex_unlock(&stream_mutex);

    notify_play_state(false);
}

static void sink_dma_init(void)
{
    /* RockboxPCM is created from the Rockbox thread so that it lives as
     * long as the service */
    JNIEnv e = *env_ptr;
    (*env_ptr)->GetJavaVM(env_ptr, &jvm);
    jclass cls = e->FindClass(env_ptr, "org/rockbox/RockboxPCM");
    RockboxPCM_class = e->NewGlobalRef(env_ptr, cls);
    jmethodID constructor = e->GetMethodID(env_ptr, RockboxPCM_class, "<init>", "()V");
    jobject obj = e->NewObject(env_ptr, RockboxPCM_class, constructor);
    RockboxPCM_instance = e->NewGlobalRef(env_ptr, obj);
    set_volume_method = e->GetMethodID(env_ptr, RockboxPCM_class, "set_volume", "(I)I");
    play_state_method = e->GetMethodID(env_ptr, RockboxPCM_class, "play_state", "(Z)V");

    have_aaudio = load_aaudio();

    pthread_t t;
    pthread_create(&t, NULL, audio_thread, NULL);
    pthread_detach(t);
}

static void sink_dma_postinit(void)
{
}

int pcm_set_mixer_volume(int volume)
{
    return (*env_ptr)->CallIntMethod(env_ptr, RockboxPCM_instance, set_volume_method, volume);
}

/*
 * release audio resources */
void pcm_shutdown(void)
{
    pthread_mutex_lock(&stream_mutex);
    playing = false;
    close_stream();
    pthread_mutex_unlock(&stream_mutex);
}

struct pcm_sink builtin_pcm_sink = {
    .caps = {
        .samprs       = hw_freq_sampr,
        .num_samprs   = HW_NUM_FREQ,
        .default_freq = HW_FREQ_DEFAULT,
        .volume_type  = PCM_NATIVE_VOLUME_TYPE,
    },
    .ops = {
        .init     = sink_dma_init,
        .postinit = sink_dma_postinit,
        .set_freq = sink_set_freq,
        .lock     = sink_lock,
        .unlock   = sink_unlock,
        .play     = sink_dma_start,
        .stop     = sink_dma_stop,
    },
};

JNIEXPORT void JNICALL
Java_org_rockbox_RockboxPCM_postVolumeChangedEvent(JNIEnv *env,
                                                   jobject this,
                                                   jint volume)
{
    (void) env;
    (void) this;
    /* for the main queue, the volume will be available through
     * button_get_data() */
    queue_broadcast(SYS_VOLUME_CHANGED, volume);
}

JNIEXPORT void JNICALL
Java_org_rockbox_RockboxPCM_nativeSetGain(JNIEnv *env, jobject this,
                                          jfloat gain)
{
    (void) env;
    (void) this;
    pcm_gain = gain;
}
