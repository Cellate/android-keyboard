# Voxis - Dual-Mode Offline Dictation, Pipeline Transparency & Configurable Model Parameters

Fix the zero-transcription issue across de-googled Android devices (GrapheneOS, CalyxOS, LineageOS, AOSP) by implementing two distinct dictation modes, behind-the-scenes pipeline visualization, and a comprehensive Voice Model Parameters settings panel with recommended defaults pre-configured.

## User Review & Critical Decisions

> [!IMPORTANT]
> The original app produced zero transcribed words because `DictationClient` relied on Android's `SpeechRecognizer` (which is absent on de-googled ROMs) and an empty stub inference method that discarded audio frames. Furthermore, model parameters were previously hardcoded without user controls. This plan resolves all root causes and exposes comprehensive, user-editable model parameters.

- **Confirmed Decision 1 (Two Operating Modes)**:
  - **Mode 1: Record-then-Process (Batch/Push-to-Talk)**: Tap mic to start recording, speak, tap mic to finish. Audio is frozen, validated, and dispatched to the model with step-by-step visual progress and latency metrics.
  - **Mode 2: Live Real-Time Streaming**: Words appear immediately as spoken (<100ms latency), with sentence-level punctuation and formatting finalized during natural speech pauses.
- **Confirmed Decision 2 (Configurable Voice Model Parameters with Recommended Defaults)**:
  - All critical voice model, audio capture, and decoding parameters are made fully editable in a dedicated **"Model & Audio Configuration"** panel:
    - **CPU Thread Count**: (Recommended: `4 Threads`, adjustable `1` to `8`)
    - **VAD Silence Endpoint**: (Recommended: `280 ms`, adjustable `150 ms` to `800 ms`)
    - **Language & Dialect**: (Recommended: `Hinglish (Code-Mixed)`, options: `English`, `Hindi`)
    - **Hinglish Initial Prompt Conditioning**: (Pre-filled with recommended conversational prompt, user editable)
    - **Decoding Strategy**: (Recommended: `Greedy Search (Low Latency)`, option: `Beam Search`)
    - **Auto-Punctuation & Capitalization**: (Recommended: `ON`)
    - **Microphone Sensitivity / VAD Threshold**: (Recommended: `Medium`, options: `Low`, `High`)
  - All options feature a **"Reset to Recommended"** button for instant one-tap restoration.
- **Confirmed Decision 3 (Zero-Failure Architecture)**: In addition to the GGML Whisper model loader, `:dictation-sdk` embeds a local offline acoustic/phonetic speech decoder to guarantee that the app never fails silently or shows an empty screen even before an external model file is downloaded.
- **Confirmed Decision 4 (Scope & Web Companion)**: The web server (`server.js`) remains a lightweight presentation preview, and 100% of functional engineering is focused on the native Android application and `:dictation-sdk`.

---

## 1. Overview & Core Concept

- **What It Does**: Provides 100% private, on-device speech-to-text dictation for privacy-hardened Android devices. Supports conversational code-mixed Hinglish, English, and Hindi.
- **The Problem Solved**: Users saw audio waveforms moving but received zero transcribed words, with no indication of what happened to the audio. Model parameters were hardcoded and inaccessible.
- **The Solution**: 
  1. Fix the audio-to-inference routing in `:dictation-sdk` and `TranscriptionViewModel`.
  2. Implement **Mode 1 (Record-then-Process)** with a dynamic **Pipeline Inspector** showing real-time stages (Audio Capture → Normalization → Model Execution → Output).
  3. Implement **Mode 2 (Live Real-Time Streaming)** showing live words as you speak with natural pause endpointing.
  4. Provide a rich **Voice Model Parameters** modal/sheet pre-populated with recommended defaults and real-time parameter persistence.
  5. Ensure guaranteed transcription output with both external Whisper models and the built-in offline acoustic engine.

---

## 2. User Experience & Visual Design

### Key User Flows

#### Flow A: Mode 1 — Record-then-Process (Batch Dictation)
1. User selects **"Batch (Record → Process)"** toggle in Studio tab.
2. User taps large **Microphone Button** (turns glowing Emerald with "Recording..." status).
3. Audio waveform bars animate alongside live buffer statistics (`3.4s / 54,400 samples`, `-14 dB RMS`, `Speech Active`).
4. User taps the **Microphone Button** again to finish speaking.
5. The UI enters **"Processing Pipeline"** state with animated step badges:
   - `[✓] Audio Captured (3.4s @ 16kHz PCM Mono)`
   - `[⚙] Model Inference: Processing audio frames...`
   - `[✓] Decoded in 240ms (RTF: 0.07x)`
6. The transcribed text immediately appears in the main canvas with word count and copy/share actions.

#### Flow B: Mode 2 — Live Real-Time Streaming
1. User selects **"⚡ Live Real-Time"** toggle in Studio tab.
2. User taps **Microphone Button**.
3. As speech begins, words appear dynamically in real time on the screen (`<100ms` latency).
4. When the user takes a natural breath (pause threshold ~280ms), the sentence is automatically punctuated, capitalized, and stabilized.
5. User taps stop or stops speaking; final transcript is preserved.

#### Flow C: Model & Audio Parameters Configuration
1. User taps the **Settings / Tuning Icon (Tune/Sliders)** in the Studio header.
2. An elegant modal bottom sheet opens displaying all configurable options grouped into logical categories:
   - **Inference & Compute**:
     - CPU Inference Threads: Slider / Selector with `(Recommended: 4)` badge.
     - Decoding Strategy: Greedy (Fastest) vs Beam Search.
   - **Voice Activity Detection (VAD)**:
     - Silence Endpoint Pause: Slider with `(Recommended: 280ms)` badge.
     - VAD Sensitivity: Low / Medium / High.
   - **Language & Lexicon**:
     - Language Code: Hinglish (`hi-en`) / English (`en`) / Hindi (`hi`).
     - Initial Prompt Conditioning: Text field pre-populated with conversational Hinglish prompt.
   - **Formatting**:
     - Auto-Punctuation Switch `(Recommended: ON)`.
     - Auto-Capitalization Switch `(Recommended: ON)`.
3. A **"Reset to Recommended"** button is always accessible to restore optimal defaults.

---

## 3. Key Technical Decisions & Trade-Offs

- **Decision 1: Direct State Binding via TranscriptionViewModel**:
  - *Chosen Approach*: Replace the redundant `DictationClient` wrapper inside `MainActivity.kt` with `TranscriptionViewModel` which manages `AudioRecorderManager`, `HybridHinglishEngine`, and StateFlows directly.
  - *Why*: Eliminates the dead-end `SpeechRecognizer` dependency that fails on de-googled phones and guarantees the UI receives live events from the actual audio recorder.
- **Decision 2: Centralized User Model Preferences (`DictationModelConfig`)**:
  - *Chosen Approach*: Create a persistent configuration data class stored in Android `SharedPreferences` that immediately applies thread counts, VAD pauses, prompts, and decoding settings to the active engine.
  - *Why*: Allows users to tweak performance on varying hardware (e.g. low-end quad-core vs high-end octa-core phones) while guaranteeing safe, verified defaults out of the box.
- **Decision 3: Built-In Offline Acoustic Engine**:
  - *Chosen Approach*: Embed an on-device phonetic and lexicon acoustic decoder within `StreamingAcousticEngine` and `WhisperInferenceEngine` so that even without a downloaded 142MB GGML file, speech is accurately transcribed locally.
  - *Why*: Guarantees that the app never fails to transcribe words out of the box, fulfilling the non-negotiable de-googled offline requirement.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                          VOXIS ANDROID APP                             │
│                                                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                     MainActivity & Compose UI                    │  │
│  │   • Mode Selector: [Batch (Record→Process)] | [Live Real-Time]    │  │
│  │   • Live Waveforms & Audio Decibel Meter                         │  │
│  │   • Pipeline Inspector: Stages, VAD, Buffer Size, Latency        │  │
│  │   • Voice Model Settings Sheet (Threads, VAD, Prompt, Defaults)  │  │
│  │   • Live / Finalized Transcript Canvas                           │  │
│  └─────────────────────────────────▲────────────────────────────────┘  │
│                                    │ StateFlow / Config Events         │
│  ┌─────────────────────────────────▼────────────────────────────────┐  │
│  │                     TranscriptionViewModel                       │  │
│  │   • State: isRecording, pipelineStage, bufferStats, liveText     │  │
│  │   • ModelConfig: threads, vadEndpointMs, prompt, language        │  │
│  │   • Mode Arbitration: BATCH vs STREAMING                         │  │
│  └─────────────────────────────────▲────────────────────────────────┘  │
│                                    │                                   │
│  ┌─────────────────────────────────▼────────────────────────────────┐  │
│  │                         :dictation-sdk                           │  │
│  │                                                                  │  │
│  │  ┌───────────────────────┐         ┌──────────────────────────┐  │  │
│  │  │ AudioRecorderManager  │         │ VoiceActivityDetector    │  │  │
│  │  │ 16kHz PCM Mono Stream │────────►│ Configurable Silence ms │  │  │
│  │  └──────────┬────────────┘         └────────────┬─────────────┘  │  │
│  │             │ 100ms frames                      │ Endpoints      │  │
│  │             ▼                                   ▼                │  │
│  │  ┌────────────────────────────────────────────────────────────┐  │  │
│  │  │                 Dual-Mode Transcription Pipeline           │  │  │
│  │  │  • Mode 1 (Batch): Freeze buffer -> Multi-stage inference  │  │  │
│  │  │  • Mode 2 (Live): Continuous 100ms frame acoustic stream   │  │  │
│  │  └──────────────────────────────┬─────────────────────────────┘  │  │
│  │                                 │                                │  │
│  │             ┌───────────────────┴───────────────────┐            │  │
│  │             ▼                                       ▼            │  │
│  │  ┌───────────────────────┐             ┌──────────────────────┐  │  │
│  │  │  WhisperNativeEngine  │             │ Embedded Offline     │  │  │
│  │  │  Configurable threads,│             │ Acoustic Engine      │  │  │
│  │  │  prompt & language    │             │ Real phonetic decode │  │  │
│  │  └───────────────────────┘             └──────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

### Key Data Models & State Structure

```kotlin
data class VoiceModelConfig(
    val nThreads: Int = 4,                       // Recommended: 4
    val vadSilenceEndpointMs: Long = 280L,      // Recommended: 280ms
    val languageCode: String = "hi-en",         // Recommended: Hinglish
    val initialPrompt: String = "Hinglish conversational tone: yaar, theek hai, kya scene hai, kaise ho, meeting, schedule.",
    val autoPunctuate: Boolean = true,          // Recommended: ON
    val autoCapitalize: Boolean = true,         // Recommended: ON
    val vadSensitivity: VadSensitivity = VadSensitivity.MEDIUM, // Recommended: MEDIUM
    val decodingStrategy: DecodingStrategy = DecodingStrategy.GREEDY // Recommended: GREEDY
)

enum class DictationMode {
    RECORD_THEN_PROCESS, // Mode 1: Push-to-talk batch processing
    LIVE_REALTIME        // Mode 2: Continuous live streaming
}

enum class PipelineStage {
    IDLE,
    RECORDING,
    ANALYZING_AUDIO,
    DISPATCHING_MODEL,
    INFERENCE_RUNNING,
    FORMATTING_OUTPUT,
    COMPLETED,
    ERROR
}

data class PipelineMetrics(
    val audioDurationSeconds: Float = 0f,
    val sampleCount: Int = 0,
    val peakDecibels: Float = -60f,
    val inferenceTimeMs: Long = 0L,
    val realTimeFactor: Float = 0f,
    val wordCount: Int = 0,
    val activeEngineDescription: String = "Embedded Offline Engine"
)
```

### Execution Steps
1. **Engine Implementation (`dictation-sdk`)**:
   - Enhance `StreamingAcousticEngine.kt` to decode real phonetic and word tokens from PCM audio frames.
   - Enhance `VadEndpointedWhisperEngine.kt` and `WhisperInferenceEngine.kt` with configurable threads, silence thresholds, and prompt conditioning.
   - Implement `VoiceModelConfig` model and repository with recommended defaults and SharedPreferences persistence.
2. **ViewModel Architecture (`app`)**:
   - Upgrade `TranscriptionViewModel.kt` with the two explicit modes (`RECORD_THEN_PROCESS` and `LIVE_REALTIME`), stage transitions, buffer statistics, model configuration flows, and latency computation.
3. **UI Integration (`MainActivity.kt`)**:
   - Bind `MainActivity.kt` and `StudioScreen` directly to `TranscriptionViewModel`.
   - Add the Dual-Mode toggle, **Pipeline Inspector** card showing behind-the-scenes progress, and the **Voice Model Parameters Sheet** with pre-configured recommended badges and a "Reset to Recommended" action.
4. **Documentation & AI Instructions**:
   - Update `AI_INSTRUCTIONS.txt` with architectural decisions, configuration options, and changelog.
5. **Compilation Verification**:
   - Verify code integrity and build the applet via `compile_applet`.
