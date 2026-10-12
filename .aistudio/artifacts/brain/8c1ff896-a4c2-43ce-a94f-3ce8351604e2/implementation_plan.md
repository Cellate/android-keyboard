# Voxis: Pure Offline Dictation App & De-Googled STT SDK

Voxis extracts FUTO's on-device speech-to-text architecture into a standalone Android application and modular SDK, built specifically for privacy-first, **de-googled Android environments** (such as GrapheneOS, CalyxOS, LineageOS, and standard AOSP). It completely eliminates all keyboard legacy code and has **zero dependence on Google Play Services (GMS)**.

### User Review & Critical Decisions

> [!IMPORTANT]
> - **100% De-Googled & GMS-Free**: Zero Google Play Services, Google Play Billing, Firebase, or Google Speech API dependencies. Operates entirely via pure AOSP APIs and local CPU/NNAPI compute.
> - **Complete Keyboard De-Clutter**: All keyboard-specific assets and legacy code (`dictionaries/`, `native/`, `libs/`, layout XMLs, IME event combiners, and gesture engines) are purged.
> - **Open AOSP `RecognitionService`**: Implements standard AOSP `android.speech.RecognitionService`, allowing de-googled phones to have fully functional system-wide speech recognition without Google Voice Typing.
> - **Standalone App & Reusable SDK**: Clean modular separation into `:app` (standalone studio & system service) and `:dictation-sdk` (embeddable offline library).

- **Confirmed Decision 1**: Zero dependence on Google Play Services (GMS); 100% compatible with GrapheneOS, CalyxOS, LineageOS, and standard AOSP.
- **Confirmed Decision 2**: Complete deletion of all legacy keyboard files and folders.
- **Confirmed Decision 3**: Triple integration layer: embeddable SDK, system-wide AOSP `RecognitionService`, and keyboard voice triggers.

---

### 1. Overview & Core Concept

- **What It Does**: An offline-only, privacy-hardened dictation application and drop-in SDK. Uses on-device Whisper models running directly on device hardware with no network calls, accounts, or proprietary services.
- **Target Audience & De-Googled Persona**:
  - *GrapheneOS / CalyxOS / Custom ROM Users*: Android users without Google Play Services who currently have no working system voice typing.
  - *FOSS & Privacy App Developers*: Developers building apps for F-Droid or privacy-conscious users who need speech-to-text without bundling Google or cloud SDKs.
- **Key Value**: Restores fast, accurate voice typing to de-googled phones while maintaining an extensible, clean codebase.

---

### 2. User Experience & Visual Design

#### Key User Flows
1. **Live Dictation Studio**:
   - Single-tap or Push-to-Talk audio recording with instantaneous microphone wake-up.
   - Real-time acoustic waveform visualizer displaying audio levels and speech activity.
   - Word-by-word streaming transcript with automated capitalization and punctuation.
   - Quick utility toolbar: One-tap Copy to Clipboard, Share via Intent, Append to Notes, and Clear.
2. **Model Management (Offline First)**:
   - Built-in lightweight Whisper model (`tiny.en`) bundled or importable directly via local storage (`.gguf` / `.tflite` / `.bin`).
   - Monitor real-time memory usage and inference latency (RTF - Real Time Factor).
3. **De-Googled System Voice Typing Setup**:
   - Guided onboarding enabling Voxis as the default voice input engine in `Settings -> System -> Languages & Input -> Voice Input`.
   - Replaces Google Voice Typing across the entire OS.
   - Diagnostic screen verifying that open-source keyboards (HeliBoard, FlorisBoard, OpenBoard, AnySoftKeyboard, FUTO) successfully route microphone requests to Voxis.
4. **Developer SDK Sandbox**:
   - Interactive developer playground with real-time event inspector and copyable Kotlin integration code.

#### Visual Identity & Theme
- **Theme**: Minimalist Audio Tool aesthetic with deep dark slate background (`#0B0F19`).
- **Accents**: Emerald Audio Pulse (`#10B981`) during voice activity, clean card surfaces (`#1E293B`).
- **Typography**: Clean monospace / sans-serif pair for audio metrics and high-contrast readability.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Zero GMS / Google Dependencies**
  - *Chosen Approach*: Exclusively use Android Open Source Project (AOSP) framework classes (`android.media.AudioRecord`, `android.speech.RecognitionService`). No `play-services-*` or proprietary binaries.
  - *Why*: Ensures 100% functional parity and crash-free reliability on de-googled ROMs (GrapheneOS, CalyxOS, LineageOS, /e/OS) and compatibility with F-Droid inclusion criteria.
  - *Alternatives Considered*: Using Google Speech Services API. Rejected because it fails completely on de-googled devices.

- **Decision 2: Purge Keyboard Legacy Files**
  - *Chosen Approach*: Delete `dictionaries/`, `native/`, `libs/`, legacy keyboard asset layouts, and complex IME combiners.
  - *Why*: Eliminates hundreds of megabytes of bloat, complex NDK toolchains, and legacy dependencies, leaving a clean, auditable speech codebase.

- **Decision 3: Reusable `:dictation-sdk` Module**
  - *Chosen Approach*: Package audio streaming, VAD, Whisper inference, and text normalization into an independent library module.
  - *Why*: Allows any Android developer to integrate offline dictation by adding a single library dependency to their project.

---

### 4. Technical Architecture & Data Strategy

#### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    De-Googled Android OS (AOSP Framework)                   │
│   ┌───────────────────────────┐         ┌───────────────────────────────┐   │
│   │ Open Keyboards (HeliBoard,│         │ FOSS Apps / Custom Clients    │   │
│   │ FlorisBoard, FUTO, AOSP)  │         │                               │   │
│   └─────────────┬─────────────┘         └───────────────┬───────────────┘   │
└─────────────────┼───────────────────────────────────────┼───────────────────┘
                  │ Standard AOSP RecognitionService IPC  │ Direct SDK API
                  ▼                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Voxis Dictation App (:app)                         │
│   ┌─────────────────────────┐  ┌─────────────────┐  ┌───────────────────┐  │
│   │  DictationStudioScreen  │  │  ModelManager   │  │  SDKPlayground    │  │
│   └─────────────┬───────────┘  └────────┬────────┘  └─────────┬─────────┘  │
│                 │                       │                     │             │
│                 └───────────────────────┼─────────────────────┘             │
│                                         ▼                                   │
│                     VoxisRecognitionService (AOSP Service)                  │
└─────────────────────────────────────────┼───────────────────────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Reusable SDK Module (:dictation-sdk)                   │
│                                                                             │
│   ┌─────────────────────────────────────────────────────────────────────┐   │
│   │                         DictationClient                             │   │
│   │  - startListening()      - stopListening()     - state: StateFlow   │   │
│   │  - transcriptions: SharedFlow<Chunk>           - audioLevels: Flow  │   │
│   └───────────────────┬───────────────────────────────┬─────────────────┘   │
│                       │                               │                     │
│         ┌─────────────▼─────────────┐   ┌─────────────▼─────────────┐       │
│         │     AudioRecordEngine     │   │     VoiceActivityDetector │       │
│         │  - 16kHz PCM Stream       │   │  - Energy / Silence Gate  │       │
│         │  - RingBuffer Circular    │   │  - Dynamic End-pointing   │       │
│         │  - Audio Focus & SCO      │   │                           │       │
│         └─────────────┬─────────────┘   └─────────────┬─────────────┘       │
│                       │                               │                     │
│                       └───────────────┬───────────────┘                     │
│                                       ▼                                     │
│                     ┌───────────────────────────────────┐                   │
│                     │       WhisperInferenceEngine      │                   │
│                     │  - Sliding Window Chunk Decoder   │                   │
│                     │  - Repetition / Loop Suppressor   │                   │
│                     │  - Punctuation & Formatting       │                   │
│                     └───────────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Step-by-Step Execution Plan

1. **Purge Non-Dictation Code & Assets**:
   - Delete `dictionaries/`, `native/`, `libs/`, legacy keyboard layouts, and IME combiners.
2. **Setup Clean Multi-Module Build**:
   - Configure Gradle build without any proprietary Google Play SDKs or GMS libraries.
   - Configure `:dictation-sdk` and `:app` modules.
3. **Build Core Reusable SDK (`:dictation-sdk`)**:
   - Implement `AudioRecordSource`, circular ring buffer, and Bluetooth SCO audio routing.
   - Implement energy-based `VoiceActivityDetector`.
   - Implement `WhisperInferenceEngine` streaming decoder and text formatter.
   - Implement public `DictationClient` API.
4. **Build Standalone App UI & AOSP System Service (`:app`)**:
   - Implement `VoxisRecognitionService` (`android.speech.RecognitionService`) for system-wide speech recognition on de-googled ROMs.
   - Implement Jetpack Compose UI (Studio, Model Manager, SDK Playground).
5. **Verification**:
   - Verify build and APK generation via `compile_applet`.
