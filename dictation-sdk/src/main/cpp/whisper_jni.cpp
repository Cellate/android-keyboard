#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// Forward declaration of whisper.cpp C-API structures
// Compatible with whisper.cpp and GGML runtime
#ifdef __cplusplus
extern "C" {
#endif

struct whisper_context;
struct whisper_state;

enum whisper_sampling_strategy {
    WHISPER_SAMPLING_GREEDY,
    WHISPER_SAMPLING_BEAM_SEARCH,
};

struct whisper_context_params {
    bool use_gpu;
    bool flash_attn;
    int  gpu_device;
    bool dtw_token_timestamps;
    int  dtw_aheads_preset;
    int  dtw_n_top;
};

struct whisper_full_params {
    enum whisper_sampling_strategy strategy;

    int n_threads;
    int n_max_text_ctx;
    int offset_ms;
    int duration_ms;

    bool translate;
    bool no_context;
    bool no_timestamps;
    bool single_segment;
    bool print_special;
    bool print_progress;
    bool print_realtime;
    bool print_timestamps;

    // Token & prompt steering
    const char * token_timestamps;
    float  thold_pt;
    float  thold_ptsum;
    int    max_len;
    bool   split_on_word;
    int    max_tokens;

    // Decoding temperature
    float  temperature;
    float  max_initial_ts;
    float  length_penalty;

    // Language & prompt conditioning
    const char * language;
    bool   detect_language;
    bool   suppress_blank;
    bool   suppress_non_speech_tokens;

    const char * initial_prompt;
    const int  * prompt_tokens;
    int          prompt_n_tokens;
};

// Functions from whisper.h
whisper_context * whisper_init_from_file_with_params(const char * path_model, struct whisper_context_params params);
whisper_context * whisper_init_from_buffer_with_params(void * buffer, size_t buffer_size, struct whisper_context_params params);
void whisper_free(whisper_context * ctx);
struct whisper_context_params whisper_context_default_params();
struct whisper_full_params whisper_full_default_params(enum whisper_sampling_strategy strategy);
int whisper_full(whisper_context * ctx, struct whisper_full_params params, const float * samples, int n_samples);
int whisper_full_n_segments(whisper_context * ctx);
const char * whisper_full_get_segment_text(whisper_context * ctx, int i_segment);

#ifdef __cplusplus
}
#endif

// Thread safety mutex for native context inference
static std::mutex g_inference_mutex;

// Helper to convert jstring to std::string
static std::string jstring_to_string(JNIEnv *env, jstring jStr) {
    if (!jStr) return "";
    const char *chars = env->GetStringUTFChars(jStr, nullptr);
    std::string str(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    return str;
}

extern "C" {

/**
 * Initialize Whisper context from a local filesystem model file.
 * Signature: (Ljava/lang/String;I)J
 */
JNIEXPORT jlong JNICALL
Java_org_voxis_dictation_engine_WhisperNativeEngine_nativeInitFromFile(
        JNIEnv *env,
        jobject /* this */,
        jstring model_path_jstr,
        jint n_threads) {

    if (!model_path_jstr) {
        LOGE("Model path is null");
        return 0;
    }

    std::string model_path = jstring_to_string(env, model_path_jstr);
    LOGI("Loading Whisper GGML model from path: %s", model_path.c_str());

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // Pure CPU on mobile devices for battery and thermal efficiency

    whisper_context *ctx = whisper_init_from_file_with_params(model_path.c_str(), cparams);
    if (!ctx) {
        LOGE("Failed to initialize whisper_context from file: %s", model_path.c_str());
        return 0;
    }

    LOGI("Whisper model loaded successfully. Context pointer: %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Initialize Whisper context directly from Android Assets buffer.
 * Signature: (Landroid/content/res/AssetManager;Ljava/lang/String;I)J
 */
JNIEXPORT jlong JNICALL
Java_org_voxis_dictation_engine_WhisperNativeEngine_nativeInitFromAsset(
        JNIEnv *env,
        jobject /* this */,
        jobject asset_manager_obj,
        jstring asset_path_jstr,
        jint n_threads) {

    if (!asset_manager_obj || !asset_path_jstr) {
        LOGE("AssetManager or asset path is null");
        return 0;
    }

    std::string asset_path = jstring_to_string(env, asset_path_jstr);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, asset_manager_obj);
    if (!asset_manager) {
        LOGE("Failed to get AAssetManager pointer");
        return 0;
    }

    AAsset *asset = AAssetManager_open(asset_manager, asset_path.c_str(), AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open asset: %s", asset_path.c_str());
        return 0;
    }

    size_t size = AAsset_getLength(asset);
    const void *buffer = AAsset_getBuffer(asset);
    if (!buffer || size == 0) {
        LOGE("Failed to buffer asset or asset is empty: %s", asset_path.c_str());
        AAsset_close(asset);
        return 0;
    }

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context *ctx = whisper_init_from_buffer_with_params(const_cast<void*>(buffer), size, cparams);
    // Note: Do not close asset while buffer is memory-mapped if whisper depends on it.
    if (!ctx) {
        LOGE("Failed to initialize whisper_context from asset buffer: %s", asset_path.c_str());
        AAsset_close(asset);
        return 0;
    }

    LOGI("Whisper model loaded from asset buffer (%zu bytes). Context: %p", size, ctx);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Transcribe 16kHz float PCM audio buffer with Hinglish prompt conditioning.
 * Signature: (J[FLjava/lang/String;Ljava/lang/String;ZI)Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL
Java_org_voxis_dictation_engine_WhisperNativeEngine_nativeTranscribe(
        JNIEnv *env,
        jobject /* this */,
        jlong context_ptr,
        jfloatArray audio_data,
        jstring initial_prompt_jstr,
        jstring language_jstr,
        jboolean translate,
        jint n_threads) {

    if (context_ptr == 0) {
        LOGE("whisper_context pointer is NULL");
        return env->NewStringUTF("");
    }

    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);

    jsize sample_count = env->GetArrayLength(audio_data);
    if (sample_count <= 0) {
        return env->NewStringUTF("");
    }

    jfloat *samples = env->GetFloatArrayElements(audio_data, nullptr);
    if (!samples) {
        LOGE("Failed to retrieve audio float array elements");
        return env->NewStringUTF("");
    }

    std::string prompt_str = jstring_to_string(env, initial_prompt_jstr);
    std::string lang_str = jstring_to_string(env, language_jstr);
    if (lang_str.empty()) {
        lang_str = "hi"; // Hindi base context for Hinglish
    }

    // Configure whisper decoding parameters
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    // Concurrency: Use requested thread count (typically 4 on 8-core ARM)
    params.n_threads = (n_threads > 0) ? n_threads : 4;
    params.translate = translate;
    params.language = lang_str.c_str();

    // Hinglish Conditioning: inject initial prompt to bias language model into conversational Latin-script Hinglish
    if (!prompt_str.empty()) {
        params.initial_prompt = prompt_str.c_str();
    }

    params.no_context = true;             // Low latency per chunk
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_non_speech_tokens = true;
    params.temperature = 0.0f;            // Deterministic greedy search
    params.thold_pt = 0.01f;

    std::string accumulated_text;

    {
        // Enforce thread safety during inference
        std::lock_guard<std::mutex> lock(g_inference_mutex);

        int result = whisper_full(ctx, params, samples, sample_count);
        if (result != 0) {
            LOGE("whisper_full failed with error code: %d", result);
        } else {
            const int n_segments = whisper_full_n_segments(ctx);
            for (int i = 0; i < n_segments; ++i) {
                const char *segment_text = whisper_full_get_segment_text(ctx, i);
                if (segment_text) {
                    accumulated_text += segment_text;
                }
            }
        }
    }

    // Release pinned float buffer back to JVM
    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);

    return env->NewStringUTF(accumulated_text.c_str());
}

/**
 * Free native Whisper context memory.
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_org_voxis_dictation_engine_WhisperNativeEngine_nativeFree(
        JNIEnv *env,
        jobject /* this */,
        jlong context_ptr) {

    if (context_ptr != 0) {
        std::lock_guard<std::mutex> lock(g_inference_mutex);
        auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
        whisper_free(ctx);
        LOGI("Whisper native context %p released successfully", ctx);
    }
}

} // extern "C"
