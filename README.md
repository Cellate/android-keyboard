# Voxis - Offline Dictation & De-Googled STT (v1.8.0)

Privacy-first, 100% on-device speech-to-text dictation application and modular Android library (`:dictation-sdk`) tailored for de-googled mobile operating systems (GrapheneOS, CalyxOS, LineageOS, and standard AOSP).

## Key Features

- **Dual Hybrid Speech Engine**: Seamless orchestration of Vosk (Kaldi ASR) for sub-100ms streaming speech recognition and Whisper GGML for high-precision code-mixed Hinglish, English, and Hindi dictation.
- **Strict Zero-Dummy Guarantee**: Never uses canned or simulated words. All transcription output originates from actual acoustic decoding of 16kHz PCM audio.
- **Studio Test Audio Runner**: In-app benchmark and verification harness bundled with 16kHz mono audio (`test.mp3`/`test.wav`) verifying the test utterance:
  > *"Hello, Hello mic check 1 2 3 hello kya meri awaaz ja rahi hai hello"*
  Renders live pipeline stage badges, latency in milliseconds, Real-Time Factor (RTF), and confidence score (%).
- **Resilient Local Model Management**: Supports local models (`.bin`, `.gguf`, `.onnx`, `.zip` directories) via Storage Access Framework (SAF) document picker and in-app HTTP/HTTPS direct downloader.
- **Standard AOSP `RecognitionService`**: Registers system-wide speech provider so open-source keyboards (HeliBoard, FlorisBoard, OpenBoard) can invoke Voxis for offline voice input.
- **De-Googled & Private**: Zero Google Play Services (GMS), zero network calls for transcription, zero telemetry.

## Architecture

- `:app`: Modern Jetpack Compose UI with Studio, Models, System Setup, and SDK Playground tabs.
- `:dictation-sdk`: Decoupled Android speech engine library featuring:
  - `AudioRecorderManager`: 16kHz mono PCM capture with multi-source fallback (`VOICE_RECOGNITION` → `MIC` → `DEFAULT`).
  - `HybridHinglishEngine`: Dual-engine orchestrator managing Mode 1 (Batch) and Mode 2 (Live Real-Time Streaming).
  - `VoskEngine`: Kaldi neural ASR via JNI.
  - `WhisperNativeEngine`: whisper.cpp GGML inference bridge.
  - `ModelManager`: Local model discovery, verification, downloading, and persistence.
- `server.js`: Companion web preview and APK distribution server on port 3000.

## Building & Verification

Compile using the Android Gradle Plugin:
```bash
./gradlew assembleDebug
```
Artifacts are packaged as `Voxis_v1.8.0.apk`.
