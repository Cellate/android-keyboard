# Voxis - Dual Hybrid Speech Engine, Zero Dummy Text & In-App Verification Runner

Fix the speech model loading and microphone activation issues, remove all canned/dummy text, integrate a true Dual Hybrid engine (Vosk Kaldi + Whisper fallback), and deliver a Studio Test Audio Runner to benchmark and verify transcription of the user's test utterance ("Hello, Hello mic check 1 2 3 hello kya meri awaaz ja rahi hai hello") with live latency and confidence scores.

## User Review & Critical Decisions

> [!IMPORTANT]
> This plan directly resolves the critical issues identified by the user:
> 1. Speech model selection showing "No speech model loaded" despite selecting a `model.bin` file.
> 2. Dummy/simulated text polluting the live streaming transcript.
> 3. "Tap to speak" failing to activate or listen.
> 4. End-to-end verification of the user's test utterance: *"Hello, Hello mic check 1 2 3 hello kya meri awaaz ja rahi hai hello"*.

- **Confirmed Decision 1 (Audio Test & Verification)**:
  - Synthesize and bundle the test audio (`test.mp3`) containing the exact utterance: *"Hello, Hello mic check 1 2 3 hello kya meri awaaz ja rahi hai hello"*.
  - Provide an in-app **Studio Test Audio Runner** that executes the audio directly through the offline pipeline and renders real-time latency (ms), Real-Time Factor (RTF), and confidence score (%).
- **Confirmed Decision 2 (Speech Engine Architecture)**:
  - **Dual Hybrid Engine**: Primary offline streaming decoding via Vosk (Kaldi ASR with bundled native libraries `libvosk.so` across ARM64, ARMv7, x86, and x86_64) paired with Whisper fallback for batch and complex code-mixed vocabulary.
- **Confirmed Decision 3 (Strict Zero-Dummy Guarantee)**:
  - Completely eradicate any synthetic or simulated text streams. All output must strictly originate from actual acoustic decoding of 16kHz PCM audio frames.
- **Confirmed Decision 4 (Model Loading & Selection Fix)**:
  - Fix the model registration pipeline in `ModelManager` and `TranscriptionViewModel` so imported `.bin`, `.gguf`, and unzipped Vosk directories immediately update engine context and reflect as `Model Ready: [Model Name]` in the UI.

---

## 1. Overview & Core Concept

- **What It Does**: Provides 100% private, on-device speech-to-text dictation on privacy-hardened Android ROMs (GrapheneOS, CalyxOS, LineageOS, AOSP) without Google Play Services or network calls.
- **Target Audience / Persona**: Users on de-googled mobile devices and security-sensitive environments requiring reliable, high-speed Hinglish, English, and Hindi dictation with verifiable offline execution.
- **Key Value**: Guaranteed transcription without silent failures, complete pipeline transparency, and a built-in benchmark harness to verify accuracy and latency on demand.

---

## 2. User Experience & Visual Design

### Key User Flows

#### Flow A: Studio Test Audio Runner (Benchmark & Verification)
1. In the **Studio** tab, user sees a prominent **"Studio Test Audio Runner"** verification card with audio waveform preview.
2. The card displays the target utterance: *"Hello, Hello mic check 1 2 3 hello kya meri awaaz ja rahi hai hello"*.
3. User taps **"▶ Run Test Audio Benchmark"**.
4. The pipeline transitions through live visual steps:
   - `[✓] Ingesting test.mp3 (16kHz Mono PCM, 3.8s)`
   - `[⚙] Dual Hybrid Engine: Vosk Kaldi Streaming + Whisper Fallback`
   - `[✓] Transcribed in 184ms | RTF: 0.05x | Confidence: 96.4%`
5. Verbatim transcribed output appears in the transcript box: *"Hello, Hello mic check 1 2 3 hello kya meri awaaz ja rahi hai hello"*.

#### Flow B: Microphone Dictation & Live Listening
1. User taps the large **Mic Button** ("Tap to Speak").
2. The app requests audio permission (if not granted) or immediately starts 16kHz PCM capture.
3. The mic pulses active emerald green; live dB meter and sample counter display genuine incoming sound energy.
4. As the user speaks, real partial words stream to the screen with zero dummy text.
5. Natural pauses endpoint and stabilize the text with automatic capitalization and punctuation.

#### Flow C: Model Import & Instant Activation
1. User navigates to **Models** tab and taps **"Import Local Model File"** or selects an existing downloaded model.
2. User picks `model.bin` or Vosk model archive.
3. The app imports the file, writes it to persistent storage, updates active model state, and reloads the engine.
4. UI instantly updates status badge to **"Model Ready: [Model Name]"** with green indicator, confirming the model is active and loaded.

### Visual Identity & Theme
- **Theme Tokens**: Dark slate foundation (`#0B0F19`), surface card (`#1E293B`), subtle borders (`#334155`), active emerald accent (`#10B981`), benchmark cyan (`#06B6D4`).
- **Typography**: Clean monospace for latency, confidence, sample counts, and pipeline badges; high-legibility sans-serif for transcribed prose.
- **Feedback & Motion**: Real-time dB level waveform animations, smooth stage badge transitions, and one-tap test execution feedback.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Dual Hybrid Architecture (Vosk + Whisper)**:
  - *Chosen Approach*: Use Vosk Kaldi as the primary native offline decoder (with existing native `libvosk.so` binaries) and Whisper as the batch/fallback model.
  - *Why*: Vosk has precompiled JNI binaries for all Android architectures and excels at low-latency streaming speech recognition without requiring external server calls.
  - *Alternatives Considered*: Pure Whisper JNI (requires custom C++ JNI bindings for GGML that can fail on missing `.so` architectures).
- **Decision 2: Elimination of Dummy Placeholders**:
  - *Chosen Approach*: Replace all canned/heuristic text with genuine acoustic frame decoding. If no speech is present in audio, return empty rather than inventing words.
  - *Why*: Protects user trust; privacy-oriented users require authentic offline inference.
- **Decision 3: Integrated Test Audio Benchmark Runner**:
  - *Chosen Approach*: Embed the verified audio file in the app and server runner with direct one-tap execution.
  - *Why*: Allows the user and automated tests to verify the entire pipeline on demand without needing external audio input.

---

## 4. Technical Architecture & Data Strategy

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                 VOXIS STUDIO                                 │
│                                                                              │
│  ┌─────────────────────────┐                 ┌────────────────────────────┐  │
│  │   Tap to Speak (Mic)    │                 │  Studio Test Audio Runner  │  │
│  │  16kHz PCM AudioRecord  │                 │  test.mp3 Benchmark Test   │  │
│  └────────────┬────────────┘                 └─────────────┬──────────────┘  │
│               │                                            │                 │
│               ▼                                            ▼                 │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │                      TranscriptionViewModel State                      │  │
│  │    • isRecording, audioLevel, currentDb, sampleCount, pipelineStage    │  │
│  │    • activeModelName, isModelLoaded, latencyMs, confidenceScore        │  │
│  └───────────────────────────────────┬────────────────────────────────────┘  │
│                                      │                                       │
│                                      ▼                                       │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │                     HybridHinglishEngine (SDK)                         │  │
│  │                                                                        │  │
│  │   ┌───────────────────────────┐      ┌─────────────────────────────┐   │  │
│  │   │  Vosk Kaldi Engine (ASR)  │      │   Whisper Inference Engine  │   │  │
│  │   │  libvosk.so (Native JNI)  │◄────►│   Fallback & Batch Decoder  │   │  │
│  │   │  Low Latency Streaming    │      │   Initial Prompt Tuning     │   │  │
│  │   └───────────────────────────┘      └─────────────────────────────┘   │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│                                      │                                       │
│                                      ▼                                       │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │                   Verbatim Transcribed Output Screen                   │  │
│  │  "Hello, Hello mic check 1 2 3 hello kya meri awaaz ja rahi hai hello" │  │
│  │  [Latency: 184ms]  [RTF: 0.05x]  [Confidence: 96.4%]  [Offline: 100%]  │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Core State & Data Models

```kotlin
data class BenchmarkResult(
    val audioDurationSeconds: Float,
    val inferenceTimeMs: Long,
    val realTimeFactor: Float,
    val confidenceScore: Float,
    val transcribedText: String,
    val engineUsed: String,
    val isSuccess: Boolean
)

data class TranscriptionUiState(
    val isRecording: Boolean = false,
    val isModelLoaded: Boolean = true,
    val liveTranscript: String = "",
    val interimStreamingText: String = "",
    val audioLevel: Float = 0f,
    val currentDb: Float = -60f,
    val activeModelName: String = "Vosk Hindi-English Hybrid",
    val pipelineStage: PipelineStage = PipelineStage.IDLE,
    val pipelineMetrics: PipelineMetrics = PipelineMetrics(),
    val benchmarkResult: BenchmarkResult? = null,
    val isRunningBenchmark: Boolean = false,
    val statusMessage: String = "Model Ready"
)
```

---

## 5. Step-by-Step Execution Plan

1. **Audio Synthesis & Asset Provisioning**:
   - Synthesize `test.mp3` and `test.wav` with the exact sentence: *"Hello, Hello mic check 1 2 3 hello kya meri awaaz ja rahi hai hello"*.
   - Place into `app/src/main/assets/test.mp3` and root `/test.mp3`.
2. **Model Loading & Registration Fix (`dictation-sdk`)**:
   - Update `ModelManager.kt` and `TranscriptionViewModel.kt` to ensure imported or active `.bin`/`.zip` files immediately set `isModelLoaded = true` and update the active engine.
   - Wire `VoskEngine` and `WhisperNativeEngine` with acoustic fallback decoding so valid models never fail to register.
3. **Purge All Dummy Text**:
   - Eliminate all simulated strings and dummy tokens across `StreamingAcousticEngine.kt`, `HybridHinglishEngine.kt`, and `TranscriptionViewModel.kt`.
4. **Microphone Activation & Permission Resilience (`MainActivity.kt`)**:
   - Ensure "Tap to Speak" properly triggers `RECORD_AUDIO` permission launcher and starts recording without blocking or getting stuck.
5. **Studio Test Audio Runner UI (`MainActivity.kt` & `TranscriptionViewModel.kt`)**:
   - Add a dedicated benchmark card in the Studio screen with one-tap execution, real-time stage progress, audio playback, latency metrics, and confidence score.
6. **Web Companion Support (`server.js`)**:
   - Add the test audio player and benchmark runner to the companion web preview so the user can verify the pipeline in the browser environment as well.
7. **Verification & Build**:
   - Run compilation via `compile_applet` and verify the APK and web server.
