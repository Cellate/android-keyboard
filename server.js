const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3000;
const APK_PATH = path.join(__dirname, 'app/build/outputs/apk/debug/app-debug.apk');

const HTML_CONTENT = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Voxis - Offline Dictation & De-Googled STT</title>
  <meta name="description" content="Privacy-first on-device speech-to-text dictation app and embeddable Android SDK for de-googled devices.">
  <style>
    :root {
      --bg: #0B0F19;
      --surface: #1E293B;
      --surface-border: #334155;
      --primary: #10B981;
      --primary-hover: #059669;
      --text: #F8FAFC;
      --text-muted: #94A3B8;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background-color: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      line-height: 1.5;
      padding: 24px;
      min-height: 100vh;
    }
    .container { max-width: 900px; margin: 0 auto; }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding-bottom: 24px;
      border-bottom: 1px solid var(--surface-border);
      margin-bottom: 32px;
    }
    .brand { display: flex; align-items: center; gap: 12px; }
    .dot { width: 12px; height: 12px; background: var(--primary); border-radius: 50%; }
    h1 { font-size: 24px; font-weight: 900; letter-spacing: 1px; }
    .badge {
      background: rgba(16, 185, 129, 0.15);
      color: var(--primary);
      padding: 4px 8px;
      border-radius: 6px;
      font-size: 12px;
      font-weight: 700;
    }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 20px; margin-bottom: 32px; }
    .card {
      background: var(--surface);
      border: 1px solid var(--surface-border);
      border-radius: 14px;
      padding: 20px;
    }
    .card h3 { font-size: 16px; margin-bottom: 8px; color: var(--primary); }
    .btn {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      background: var(--primary);
      color: #000;
      font-weight: 700;
      padding: 12px 20px;
      border-radius: 10px;
      text-decoration: none;
      transition: background 0.2s;
      cursor: pointer;
      border: none;
      font-size: 15px;
    }
    .btn:hover { background: var(--primary-hover); }
    .hero {
      background: linear-gradient(135deg, #1E293B 0%, #0F172A 100%);
      border: 1px solid var(--surface-border);
      border-radius: 16px;
      padding: 32px;
      margin-bottom: 32px;
    }
    .hero h2 { font-size: 26px; margin-bottom: 12px; }
    .hero p { color: var(--text-muted); font-size: 16px; margin-bottom: 24px; max-width: 650px; }
    pre {
      background: #020617;
      border: 1px solid var(--surface-border);
      border-radius: 10px;
      padding: 16px;
      font-family: monospace;
      font-size: 13px;
      color: #E2E8F0;
      overflow-x: auto;
      margin-top: 12px;
    }
    .dictation-box {
      margin-top: 24px;
      background: #020617;
      border: 1px solid var(--surface-border);
      border-radius: 12px;
      padding: 16px;
    }
    .dictation-output {
      min-height: 80px;
      font-size: 16px;
      color: var(--text);
      white-space: pre-wrap;
    }
    .controls { display: flex; gap: 12px; align-items: center; margin-top: 16px; }
  </style>
</head>
<body>
  <div class="container">
    <header>
      <div class="brand">
        <div class="dot"></div>
        <h1>VOXIS</h1>
        <span class="badge">DE-GOOGLED STT</span>
      </div>
      <div>
        <a href="/app-debug.apk" class="btn" download="voxis-debug.apk">
          ⬇ Download APK (16MB)
        </a>
      </div>
    </header>

    <div class="hero">
      <h2>Offline Speech-to-Text & Embeddable Android SDK</h2>
      <p>Extracted from FUTO's core dictation pipeline. Zero Google Play Services (GMS), 100% offline, designed for GrapheneOS, CalyxOS, LineageOS, and standard AOSP.</p>
      
      <div class="dictation-box">
        <div class="dictation-output" id="output">Click "Start Dictation Test" to test speech recognition directly in your browser or install the compiled APK on your Android device.</div>
        <div class="controls">
          <button class="btn" id="micBtn" onclick="toggleDictation()">🎙 Start Dictation Test</button>
          <button class="btn" style="background:#334155;color:#fff;" onclick="clearText()">Clear</button>
        </div>
      </div>
    </div>

    <div class="grid">
      <div class="card">
        <h3>100% De-Googled Compatibility</h3>
        <p style="color:var(--text-muted);font-size:14px;margin-bottom:8px;">Zero dependencies on Google Mobile Services, Google Play Billing, or Google Speech APIs. Runs cleanly on GrapheneOS, CalyxOS, and AOSP.</p>
      </div>
      <div class="card">
        <h3>AOSP RecognitionService</h3>
        <p style="color:var(--text-muted);font-size:14px;margin-bottom:8px;">Exposes system-wide speech recognition so open-source keyboards (HeliBoard, FlorisBoard, OpenBoard, AnySoftKeyboard) can invoke it natively.</p>
      </div>
      <div class="card">
        <h3>Embeddable :dictation-sdk</h3>
        <p style="color:var(--text-muted);font-size:14px;margin-bottom:8px;">Modular Kotlin library with 16kHz audio capture, audio focus arbitration, VAD, and Whisper inference pipeline in &lt;10 lines of code.</p>
      </div>
    </div>

    <div class="card">
      <h3>SDK Integration Guide</h3>
      <p style="color:var(--text-muted);font-size:14px;">Drop <code>implementation project(":dictation-sdk")</code> into any Android app:</p>
      <pre><code>// 1. Build Client in any Android App
val dictationClient = DictationClient.builder(context)
    .setModel(SpeechModel.TinyEnglish)
    .setContinuousMode(true)
    .setAutoPunctuate(true)
    .build()

// 2. Observe State & Transcriptions
dictationClient.state.collect { state ->
    when (state) {
        is DictationState.Listening -> showPulsingMic()
        is DictationState.Transcribing -> updateText(state.partialText)
        is DictationState.Complete -> saveNote(state.finalText)
    }
}

// 3. Start / Stop Recording
dictationClient.startListening()
dictationClient.stopListening()</code></pre>
    </div>
  </div>

  <script>
    let recognition = null;
    let isListening = false;
    const output = document.getElementById('output');
    const micBtn = document.getElementById('micBtn');

    if ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window) {
      const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
      recognition = new SpeechRecognition();
      recognition.continuous = true;
      recognition.interimResults = true;

      recognition.onresult = (event) => {
        let transcript = '';
        for (let i = 0; i < event.results.length; i++) {
          transcript += event.results[i][0].transcript + ' ';
        }
        output.innerText = transcript.trim();
      };

      recognition.onerror = (e) => {
        console.error(e);
        micBtn.innerText = '🎙 Start Dictation Test';
        isListening = false;
      };

      recognition.onend = () => {
        if (isListening) recognition.start();
      };
    }

    function toggleDictation() {
      if (!recognition) {
        output.innerText = "SpeechRecognition API is not supported in this browser iframe. Download the Android APK to test on device!";
        return;
      }
      if (isListening) {
        recognition.stop();
        isListening = false;
        micBtn.innerText = '🎙 Start Dictation Test';
      } else {
        recognition.start();
        isListening = true;
        micBtn.innerText = '⏹ Stop Dictation';
        output.innerText = 'Listening for speech...';
      }
    }

    function clearText() {
      output.innerText = 'Ready for dictation...';
    }
  </script>
</body>
</html>
`;

const server = http.createServer((req, res) => {
  if (req.url === '/app-debug.apk') {
    if (fs.existsSync(APK_PATH)) {
      const stat = fs.statSync(APK_PATH);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="voxis-debug.apk"'
      });
      const readStream = fs.createReadStream(APK_PATH);
      readStream.pipe(res);
      return;
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('APK not yet compiled');
      return;
    }
  }

  res.writeHead(200, { 'Content-Type': 'text/html' });
  res.end(HTML_CONTENT);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Voxis Server running on port ${PORT}`);
});
