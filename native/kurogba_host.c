/*
 * KuroGBA native host — a tiny libretro frontend behind JNI.
 *
 * Loads libmgba_libretro.so (built from the mGBA submodule) with dlopen,
 * drives retro_run() from a Kotlin-owned emulation thread, and marshals
 * every state-mutating operation (save states, SRAM, cheats, reset) through
 * a command queue that is executed on the emulation thread between frames.
 * That keeps the libretro single-threaded contract intact: the core is only
 * ever touched from one thread.
 *
 * Video frames are converted to RGB565 in native and polled by Kotlin via a
 * frame serial counter. Audio goes through a lock-free-ish ring buffer that
 * a Kotlin AudioTrack thread drains.
 */
#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "libretro.h"

#define LOG_TAG "KuroGBA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* Core function pointers                                              */
/* ------------------------------------------------------------------ */
typedef void (*fn_void)(void);
typedef bool (*fn_bool)(void);

static void *g_core_handle = NULL;
static void (*p_retro_init)(void);
static void (*p_retro_deinit)(void);
static unsigned (*p_retro_api_version)(void);
static void (*p_retro_get_system_info)(struct retro_system_info *);
static void (*p_retro_get_system_av_info)(struct retro_system_av_info *);
static void (*p_retro_set_environment)(retro_environment_t);
static void (*p_retro_set_video_refresh)(retro_video_refresh_t);
static void (*p_retro_set_audio_sample)(retro_audio_sample_t);
static void (*p_retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void (*p_retro_set_input_poll)(retro_input_poll_t);
static void (*p_retro_set_input_state)(retro_input_state_t);
static void (*p_retro_set_controller_port_device)(unsigned, unsigned);
static bool (*p_retro_load_game)(const struct retro_game_info *);
static void (*p_retro_unload_game)(void);
static void (*p_retro_run)(void);
static void (*p_retro_reset)(void);
static size_t (*p_retro_serialize_size)(void);
static bool (*p_retro_serialize)(void *, size_t);
static bool (*p_retro_unserialize)(const void *, size_t);
static void *(*p_retro_get_memory_data)(unsigned);
static size_t (*p_retro_get_memory_size)(unsigned);
static void (*p_retro_cheat_reset)(void);
static void (*p_retro_cheat_set)(unsigned, bool, const char *);

static bool g_initialized = false;
static bool g_loaded = false;
static bool g_need_fullpath = false;
static double g_fps = 59.7275;
static unsigned g_sample_rate = 32768;

static char g_system_dir[512] = {0};
static char g_save_dir[512] = {0};

/* ------------------------------------------------------------------ */
/* Core options (defaults captured from SET_VARIABLES)                 */
/* ------------------------------------------------------------------ */
#define MAX_VARS 96
static struct { char key[64]; char value[160]; } g_vars[MAX_VARS];
static int g_var_count = 0;

static const char *find_var(const char *key) {
    for (int i = 0; i < g_var_count; i++)
        if (strcmp(g_vars[i].key, key) == 0) return g_vars[i].value;
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Video                                                               */
/* ------------------------------------------------------------------ */
#define FB_MAX_W 240
#define FB_MAX_H 160
static uint16_t g_frame[FB_MAX_W * FB_MAX_H];
static unsigned g_frame_w = FB_MAX_W;
static unsigned g_frame_h = FB_MAX_H;
static volatile uint32_t g_frame_serial = 0;
static enum retro_pixel_format g_pixel_format = RETRO_PIXEL_FORMAT_RGB565;
static pthread_mutex_t g_frame_lock = PTHREAD_MUTEX_INITIALIZER;

/* ------------------------------------------------------------------ */
/* Audio ring buffer (stereo frames)                                   */
/* ------------------------------------------------------------------ */
#define AUDIO_RING_FRAMES 32768
static int16_t g_audio_ring[AUDIO_RING_FRAMES * 2];
static volatile uint32_t g_audio_head = 0; /* write pos (frames) */
static volatile uint32_t g_audio_tail = 0; /* read pos (frames) */
static volatile int g_fast_forward = 0;

/* ------------------------------------------------------------------ */
/* Input                                                               */
/* ------------------------------------------------------------------ */
static volatile uint16_t g_buttons = 0;

/* ------------------------------------------------------------------ */
/* Command queue (executed on the emu thread between frames)           */
/* ------------------------------------------------------------------ */
enum cmd_type {
    CMD_NONE = 0,
    CMD_SAVE_STATE,
    CMD_LOAD_STATE,
    CMD_GET_SRAM,
    CMD_SET_SRAM,
    CMD_RESET,
    CMD_APPLY_CHEAT,
    CMD_CLEAR_CHEATS
};

static struct {
    enum cmd_type type;
    uint8_t *in_data;      /* owned by the command while pending */
    size_t in_len;
    uint8_t *out_data;     /* produced by execution, freed by the waiter */
    size_t out_len;
    bool ok;
    bool done;
    bool pending;
} g_cmd;

static pthread_mutex_t g_cmd_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_cmd_posted = PTHREAD_COND_INITIALIZER;
static pthread_cond_t g_cmd_done_cond = PTHREAD_COND_INITIALIZER;
static pthread_t g_emu_thread;
static bool g_emu_thread_known = false;

/* ------------------------------------------------------------------ */
/* libretro callbacks                                                  */
/* ------------------------------------------------------------------ */
static void kuro_log(enum retro_log_level level, const char *fmt, ...) {
    int prio = ANDROID_LOG_INFO;
    if (level == RETRO_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == RETRO_LOG_WARN) prio = ANDROID_LOG_WARN;
    else if (level == RETRO_LOG_DEBUG) prio = ANDROID_LOG_DEBUG;
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(prio, "mgba", fmt, args);
    va_end(args);
}

static bool kuro_rumble(unsigned port, enum retro_rumble_effect effect, uint16_t strength) {
    (void)port; (void)effect; (void)strength;
    return false;
}

static bool kuro_environment(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        g_pixel_format = *(const enum retro_pixel_format *)data;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
        struct retro_log_callback *cb = (struct retro_log_callback *)data;
        cb->log = kuro_log;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES: {
        const struct retro_variable *vars = (const struct retro_variable *)data;
        g_var_count = 0;
        for (int i = 0; vars && vars[i].key && g_var_count < MAX_VARS; i++) {
            const char *v = vars[i].value;
            if (!v) continue;
            /* value format: "Description; default|opt|opt" — default first */
            const char *def = strchr(v, ';');
            def = def ? def + 1 : v;
            while (*def == ' ') def++;
            char buf[160];
            size_t n = 0;
            while (def[n] && def[n] != '|' && n < sizeof(buf) - 1) { buf[n] = def[n]; n++; }
            buf[n] = 0;
            snprintf(g_vars[g_var_count].key, sizeof(g_vars[0].key), "%s", vars[i].key);
            snprintf(g_vars[g_var_count].value, sizeof(g_vars[0].value), "%s", buf);
            g_var_count++;
        }
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable *var = (struct retro_variable *)data;
        const char *val = var->key ? find_var(var->key) : NULL;
        if (!val) return false;
        var->value = val;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = false;
        return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = g_system_dir;
        return g_system_dir[0] != 0;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = g_save_dir;
        return g_save_dir[0] != 0;
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        *(unsigned *)data = RETRO_LANGUAGE_ENGLISH;
        return true;
    case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
        *(int *)data = (1 << 0) | (1 << 1); /* audio + video */
        return true;
    case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
        *(bool *)data = g_fast_forward != 0;
        return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool *)data = true;
        return true;
    case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: {
        struct retro_rumble_interface *ri = (struct retro_rumble_interface *)data;
        ri->set_rumble_state = kuro_rumble;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_MESSAGE:
    case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
        const struct retro_message *msg = (const struct retro_message *)data;
        if (msg && msg->msg) LOGI("core message: %s", msg->msg);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
    case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        return true;
    default:
        return false;
    }
}

static void kuro_video_refresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID) return;
    if (width > FB_MAX_W) width = FB_MAX_W;
    if (height > FB_MAX_H) height = FB_MAX_H;
    pthread_mutex_lock(&g_frame_lock);
    const uint8_t *src = (const uint8_t *)data;
    if (g_pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
        for (unsigned y = 0; y < height; y++)
            memcpy(&g_frame[y * width], src + y * pitch, (size_t)width * 2);
    } else if (g_pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
        for (unsigned y = 0; y < height; y++) {
            const uint32_t *row = (const uint32_t *)(src + y * pitch);
            for (unsigned x = 0; x < width; x++) {
                uint32_t px = row[x];
                unsigned r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                g_frame[y * width + x] = (uint16_t)(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
            }
        }
    } else { /* 0RGB1555 */
        for (unsigned y = 0; y < height; y++) {
            const uint16_t *row = (const uint16_t *)(src + y * pitch);
            for (unsigned x = 0; x < width; x++) {
                uint16_t px = row[x];
                unsigned r = (px >> 10) & 0x1F, g = (px >> 5) & 0x1F, b = px & 0x1F;
                g_frame[y * width + x] = (uint16_t)((r << 11) | (g << 6) | b);
            }
        }
    }
    g_frame_w = width;
    g_frame_h = height;
    g_frame_serial++;
    pthread_mutex_unlock(&g_frame_lock);
}

static void kuro_audio_sample(int16_t left, int16_t right) {
    int16_t frame[2] = {left, right};
    if (g_fast_forward) return;
    uint32_t head = g_audio_head;
    uint32_t next = (head + 1) % AUDIO_RING_FRAMES;
    if (next == g_audio_tail) return; /* full: drop */
    g_audio_ring[head * 2] = frame[0];
    g_audio_ring[head * 2 + 1] = frame[1];
    g_audio_head = next;
}

static size_t kuro_audio_batch(const int16_t *data, size_t frames) {
    if (g_fast_forward) return frames;
    for (size_t i = 0; i < frames; i++) {
        uint32_t head = g_audio_head;
        uint32_t next = (head + 1) % AUDIO_RING_FRAMES;
        if (next == g_audio_tail) break; /* full: drop the rest */
        g_audio_ring[head * 2] = data[i * 2];
        g_audio_ring[head * 2 + 1] = data[i * 2 + 1];
        g_audio_head = next;
    }
    return frames;
}

static void kuro_input_poll(void) { /* state is pushed from Kotlin */ }

static int16_t kuro_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port == 0 && device == RETRO_DEVICE_JOYPAD && id < 16)
        return (g_buttons >> id) & 1;
    return 0;
}

/* ------------------------------------------------------------------ */
/* Command execution (emu thread only)                                 */
/* ------------------------------------------------------------------ */
static void exec_cmd(void) {
    g_cmd.ok = false;
    g_cmd.out_data = NULL;
    g_cmd.out_len = 0;
    if (!g_loaded && g_cmd.type != CMD_NONE) {
        g_cmd.done = true;
        return;
    }
    switch (g_cmd.type) {
    case CMD_SAVE_STATE: {
        size_t size = p_retro_serialize_size();
        if (size > 0) {
            uint8_t *buf = (uint8_t *)malloc(size);
            if (buf && p_retro_serialize(buf, size)) {
                g_cmd.out_data = buf;
                g_cmd.out_len = size;
                g_cmd.ok = true;
            } else {
                free(buf);
            }
        }
        break;
    }
    case CMD_LOAD_STATE:
        if (g_cmd.in_data && g_cmd.in_len > 0)
            g_cmd.ok = p_retro_unserialize(g_cmd.in_data, g_cmd.in_len);
        break;
    case CMD_GET_SRAM: {
        void *mem = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
        size_t size = p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
        if (mem && size > 0) {
            uint8_t *buf = (uint8_t *)malloc(size);
            if (buf) {
                memcpy(buf, mem, size);
                g_cmd.out_data = buf;
                g_cmd.out_len = size;
                g_cmd.ok = true;
            }
        }
        break;
    }
    case CMD_SET_SRAM: {
        void *mem = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
        size_t size = p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
        if (mem && size > 0 && g_cmd.in_data) {
            size_t n = g_cmd.in_len < size ? g_cmd.in_len : size;
            memcpy(mem, g_cmd.in_data, n);
            g_cmd.ok = true;
        }
        break;
    }
    case CMD_RESET:
        p_retro_reset();
        g_cmd.ok = true;
        break;
    case CMD_APPLY_CHEAT: {
        if (g_cmd.in_data) {
            p_retro_cheat_reset();
            /* one cheat per line; each becomes its own index */
            char *copy = strdup((const char *)g_cmd.in_data);
            unsigned idx = 0;
            char *saveptr = NULL;
            for (char *line = strtok_r(copy, "\r\n", &saveptr); line;
                 line = strtok_r(NULL, "\r\n", &saveptr)) {
                while (*line == ' ' || *line == '\t') line++;
                size_t len = strlen(line);
                while (len > 0 && (line[len - 1] == ' ' || line[len - 1] == '\t')) line[--len] = 0;
                if (len > 0) p_retro_cheat_set(idx++, true, line);
            }
            free(copy);
            g_cmd.ok = idx > 0;
        }
        break;
    }
    case CMD_CLEAR_CHEATS:
        p_retro_cheat_reset();
        g_cmd.ok = true;
        break;
    default:
        break;
    }
    g_cmd.done = true;
}

static void process_pending_cmd(void) {
    pthread_mutex_lock(&g_cmd_lock);
    if (g_cmd.pending && !g_cmd.done) {
        exec_cmd();
        pthread_cond_signal(&g_cmd_done_cond);
    }
    pthread_mutex_unlock(&g_cmd_lock);
}

/* Post a command and wait for the emu thread to run it.
 * If called FROM the emu thread, execute inline (no deadlock). */
static bool post_cmd(enum cmd_type type, const uint8_t *in, size_t in_len,
                     uint8_t **out, size_t *out_len) {
    if (out) *out = NULL;
    if (out_len) *out_len = 0;
    if (g_emu_thread_known && pthread_equal(pthread_self(), g_emu_thread)) {
        g_cmd.type = type;
        g_cmd.in_data = (uint8_t *)in;
        g_cmd.in_len = in_len;
        exec_cmd();
        bool ok = g_cmd.ok;
        if (out) { *out = g_cmd.out_data; }
        if (out_len) { *out_len = g_cmd.out_len; }
        g_cmd.in_data = NULL;
        return ok;
    }
    pthread_mutex_lock(&g_cmd_lock);
    while (g_cmd.pending)
        pthread_cond_wait(&g_cmd_done_cond, &g_cmd_lock);
    g_cmd.type = type;
    g_cmd.in_data = NULL;
    g_cmd.in_len = 0;
    if (in && in_len > 0) {
        g_cmd.in_data = (uint8_t *)malloc(in_len);
        if (!g_cmd.in_data) { pthread_mutex_unlock(&g_cmd_lock); return false; }
        memcpy(g_cmd.in_data, in, in_len);
        g_cmd.in_len = in_len;
    }
    g_cmd.done = false;
    g_cmd.pending = true;
    pthread_cond_signal(&g_cmd_posted);
    while (!g_cmd.done)
        pthread_cond_wait(&g_cmd_done_cond, &g_cmd_lock);
    bool ok = g_cmd.ok;
    if (out) *out = g_cmd.out_data; else free(g_cmd.out_data);
    if (out_len) *out_len = g_cmd.out_len;
    free(g_cmd.in_data);
    g_cmd.in_data = NULL;
    g_cmd.out_data = NULL;
    g_cmd.pending = false;
    pthread_cond_signal(&g_cmd_done_cond); /* wake any next poster */
    pthread_mutex_unlock(&g_cmd_lock);
    return ok;
}

/* ------------------------------------------------------------------ */
/* Symbol resolution                                                   */
/* ------------------------------------------------------------------ */
#define RESOLVE(name)                                                        \
    do {                                                                     \
        p_##name = (void *)dlsym(g_core_handle, #name);                      \
        if (!p_##name) {                                                     \
            LOGE("missing symbol %s", #name);                                \
            return false;                                                    \
        }                                                                    \
    } while (0)

static bool resolve_symbols(void) {
    RESOLVE(retro_init);
    RESOLVE(retro_deinit);
    RESOLVE(retro_api_version);
    RESOLVE(retro_get_system_info);
    RESOLVE(retro_get_system_av_info);
    RESOLVE(retro_set_environment);
    RESOLVE(retro_set_video_refresh);
    RESOLVE(retro_set_audio_sample);
    RESOLVE(retro_set_audio_sample_batch);
    RESOLVE(retro_set_input_poll);
    RESOLVE(retro_set_input_state);
    RESOLVE(retro_load_game);
    RESOLVE(retro_unload_game);
    RESOLVE(retro_run);
    RESOLVE(retro_reset);
    RESOLVE(retro_serialize_size);
    RESOLVE(retro_serialize);
    RESOLVE(retro_unserialize);
    RESOLVE(retro_get_memory_data);
    RESOLVE(retro_get_memory_size);
    RESOLVE(retro_cheat_reset);
    RESOLVE(retro_cheat_set);
    p_retro_set_controller_port_device =
        (void *)dlsym(g_core_handle, "retro_set_controller_port_device");
    return true;
}

/* ------------------------------------------------------------------ */
/* JNI                                                                 */
/* ------------------------------------------------------------------ */
JNIEXPORT jboolean JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeInit(
        JNIEnv *env, jobject thiz, jstring core_path,
        jstring system_dir, jstring save_dir) {
    (void)thiz;
    if (g_initialized) return JNI_TRUE;
    const char *core = (*env)->GetStringUTFChars(env, core_path, NULL);
    const char *sys = (*env)->GetStringUTFChars(env, system_dir, NULL);
    const char *sav = (*env)->GetStringUTFChars(env, save_dir, NULL);
    snprintf(g_system_dir, sizeof(g_system_dir), "%s", sys ? sys : "");
    snprintf(g_save_dir, sizeof(g_save_dir), "%s", sav ? sav : "");

    g_core_handle = dlopen(core, RTLD_NOW | RTLD_LOCAL);
    if (!g_core_handle) {
        LOGE("dlopen failed: %s", dlerror());
        (*env)->ReleaseStringUTFChars(env, core_path, core);
        (*env)->ReleaseStringUTFChars(env, system_dir, sys);
        (*env)->ReleaseStringUTFChars(env, save_dir, sav);
        return JNI_FALSE;
    }
    (*env)->ReleaseStringUTFChars(env, core_path, core);
    (*env)->ReleaseStringUTFChars(env, system_dir, sys);
    (*env)->ReleaseStringUTFChars(env, save_dir, sav);

    if (!resolve_symbols()) return JNI_FALSE;
    if (p_retro_api_version() != RETRO_API_VERSION) {
        LOGE("libretro API mismatch");
        return JNI_FALSE;
    }
    p_retro_set_environment(kuro_environment);
    p_retro_set_video_refresh(kuro_video_refresh);
    p_retro_set_audio_sample(kuro_audio_sample);
    p_retro_set_audio_sample_batch(kuro_audio_batch);
    p_retro_set_input_poll(kuro_input_poll);
    p_retro_set_input_state(kuro_input_state);
    p_retro_init();

    struct retro_system_info info;
    memset(&info, 0, sizeof(info));
    p_retro_get_system_info(&info);
    g_need_fullpath = info.need_fullpath;
    LOGI("core: %s %s (need_fullpath=%d)",
         info.library_name ? info.library_name : "?",
         info.library_version ? info.library_version : "?",
         (int)g_need_fullpath);
    g_initialized = true;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeLoadRom(
        JNIEnv *env, jobject thiz, jbyteArray rom, jstring path_hint,
        jstring fallback_file) {
    (void)thiz;
    if (!g_initialized || g_loaded) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, rom);
    if (len <= 0) return JNI_FALSE;
    uint8_t *bytes = (uint8_t *)malloc((size_t)len);
    if (!bytes) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, rom, 0, len, (jbyte *)bytes);

    const char *hint = (*env)->GetStringUTFChars(env, path_hint, NULL);
    struct retro_game_info game;
    memset(&game, 0, sizeof(game));
    game.path = hint;
    game.data = bytes;
    game.size = (size_t)len;
    bool ok = p_retro_load_game(&game);
    if (!ok && fallback_file) {
        /* need_fullpath cores: write the ROM to a cache file and retry. */
        const char *fb = (*env)->GetStringUTFChars(env, fallback_file, NULL);
        FILE *f = fopen(fb, "wb");
        if (f) {
            fwrite(bytes, 1, (size_t)len, f);
            fclose(f);
            memset(&game, 0, sizeof(game));
            game.path = fb;
            game.data = NULL;
            game.size = 0;
            ok = p_retro_load_game(&game);
        }
        (*env)->ReleaseStringUTFChars(env, fallback_file, fb);
    }
    (*env)->ReleaseStringUTFChars(env, path_hint, hint);
    free(bytes);
    if (!ok) {
        LOGE("retro_load_game failed");
        return JNI_FALSE;
    }
    if (p_retro_set_controller_port_device)
        p_retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    struct retro_system_av_info av;
    memset(&av, 0, sizeof(av));
    p_retro_get_system_av_info(&av);
    if (av.timing.fps > 0) g_fps = av.timing.fps;
    if (av.timing.sample_rate > 0) g_sample_rate = (unsigned)av.timing.sample_rate;
    LOGI("av: %ux%u @ %.3f fps, %u Hz",
         av.geometry.base_width, av.geometry.base_height, g_fps, g_sample_rate);
    g_loaded = true;
    return JNI_TRUE;
}

/* Runs pending commands, then n frames. Called only from the emu thread. */
JNIEXPORT jint JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeStep(
        JNIEnv *env, jobject thiz, jint frames) {
    (void)env; (void)thiz;
    if (!g_emu_thread_known) {
        g_emu_thread = pthread_self();
        g_emu_thread_known = true;
    }
    process_pending_cmd();
    if (!g_loaded) return 0;
    for (jint i = 0; i < frames; i++) p_retro_run();
    return frames;
}

JNIEXPORT void JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeGetFrameInfo(
        JNIEnv *env, jobject thiz, jintArray out) {
    (void)thiz;
    jint vals[3];
    pthread_mutex_lock(&g_frame_lock);
    vals[0] = (jint)g_frame_serial;
    vals[1] = (jint)g_frame_w;
    vals[2] = (jint)g_frame_h;
    pthread_mutex_unlock(&g_frame_lock);
    (*env)->SetIntArrayRegion(env, out, 0, 3, vals);
}

/* Copies the latest RGB565 frame into a direct ByteBuffer. Returns serial. */
JNIEXPORT jint JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeCopyFrame(
        JNIEnv *env, jobject thiz, jobject dst) {
    (void)thiz;
    void *addr = (*env)->GetDirectBufferAddress(env, dst);
    jlong cap = (*env)->GetDirectBufferCapacity(env, dst);
    if (!addr) return 0;
    pthread_mutex_lock(&g_frame_lock);
    size_t need = (size_t)g_frame_w * g_frame_h * 2;
    jint serial = (jint)g_frame_serial;
    if (cap >= (jlong)need) memcpy(addr, g_frame, need);
    pthread_mutex_unlock(&g_frame_lock);
    return serial;
}

JNIEXPORT jint JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeReadAudio(
        JNIEnv *env, jobject thiz, jshortArray out) {
    (void)thiz;
    jsize cap = (*env)->GetArrayLength(env, out); /* in samples (int16) */
    uint32_t tail = g_audio_tail;
    uint32_t head = g_audio_head;
    uint32_t avail_frames = (head + AUDIO_RING_FRAMES - tail) % AUDIO_RING_FRAMES;
    jsize want_frames = cap / 2;
    if ((jsize)avail_frames > want_frames) avail_frames = (uint32_t)want_frames;
    if (avail_frames == 0) return 0;
    jshort *tmp = (jshort *)malloc((size_t)avail_frames * 2 * sizeof(jshort));
    if (!tmp) return 0;
    for (uint32_t i = 0; i < avail_frames; i++) {
        uint32_t pos = (tail + i) % AUDIO_RING_FRAMES;
        tmp[i * 2] = g_audio_ring[pos * 2];
        tmp[i * 2 + 1] = g_audio_ring[pos * 2 + 1];
    }
    g_audio_tail = (tail + avail_frames) % AUDIO_RING_FRAMES;
    (*env)->SetShortArrayRegion(env, out, 0, (jsize)avail_frames * 2, tmp);
    free(tmp);
    return (jint)avail_frames;
}

JNIEXPORT void JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeSetButtons(
        JNIEnv *env, jobject thiz, jint mask) {
    (void)env; (void)thiz;
    g_buttons = (uint16_t)mask;
}

JNIEXPORT void JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeSetFastForward(
        JNIEnv *env, jobject thiz, jboolean ff) {
    (void)env; (void)thiz;
    g_fast_forward = ff ? 1 : 0;
    if (ff) { /* drop stale audio so exiting FF stays in sync */
        g_audio_tail = g_audio_head;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeSaveState(
        JNIEnv *env, jobject thiz) {
    (void)thiz;
    uint8_t *out = NULL;
    size_t out_len = 0;
    if (!post_cmd(CMD_SAVE_STATE, NULL, 0, &out, &out_len) || !out) {
        free(out);
        return NULL;
    }
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)out_len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)out_len, (const jbyte *)out);
    free(out);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeLoadState(
        JNIEnv *env, jobject thiz, jbyteArray state) {
    (void)thiz;
    jsize len = (*env)->GetArrayLength(env, state);
    if (len <= 0) return JNI_FALSE;
    uint8_t *buf = (uint8_t *)malloc((size_t)len);
    if (!buf) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, state, 0, len, (jbyte *)buf);
    bool ok = post_cmd(CMD_LOAD_STATE, buf, (size_t)len, NULL, NULL);
    free(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeGetSaveRam(
        JNIEnv *env, jobject thiz) {
    (void)thiz;
    uint8_t *out = NULL;
    size_t out_len = 0;
    if (!post_cmd(CMD_GET_SRAM, NULL, 0, &out, &out_len) || !out) {
        free(out);
        return NULL;
    }
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)out_len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)out_len, (const jbyte *)out);
    free(out);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeSetSaveRam(
        JNIEnv *env, jobject thiz, jbyteArray data) {
    (void)thiz;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len <= 0) return JNI_FALSE;
    uint8_t *buf = (uint8_t *)malloc((size_t)len);
    if (!buf) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, data, 0, len, (jbyte *)buf);
    bool ok = post_cmd(CMD_SET_SRAM, buf, (size_t)len, NULL, NULL);
    free(buf);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeApplyCheat(
        JNIEnv *env, jobject thiz, jstring code) {
    (void)thiz;
    const char *c = (*env)->GetStringUTFChars(env, code, NULL);
    if (!c) return JNI_FALSE;
    bool ok = post_cmd(CMD_APPLY_CHEAT, (const uint8_t *)c, strlen(c) + 1, NULL, NULL);
    (*env)->ReleaseStringUTFChars(env, code, c);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeClearCheats(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    post_cmd(CMD_CLEAR_CHEATS, NULL, 0, NULL, NULL);
}

JNIEXPORT void JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeReset(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    post_cmd(CMD_RESET, NULL, 0, NULL, NULL);
}

JNIEXPORT jdouble JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeGetFps(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jdouble)g_fps;
}

JNIEXPORT jint JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeGetSampleRate(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jint)g_sample_rate;
}

/* Caller must have stopped the emu thread first. */
JNIEXPORT void JNICALL
Java_com_kyubiez96_kurogba_NativeBridge_nativeShutdown(
        JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    if (g_loaded) {
        p_retro_unload_game();
        g_loaded = false;
    }
    if (g_initialized) {
        p_retro_deinit();
        g_initialized = false;
    }
    if (g_core_handle) {
        dlclose(g_core_handle);
        g_core_handle = NULL;
    }
    g_emu_thread_known = false;
}
