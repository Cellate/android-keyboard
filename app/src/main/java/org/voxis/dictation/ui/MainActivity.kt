package org.voxis.dictation.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.voxis.dictation.client.DictationClient
import org.voxis.dictation.client.DictationListener
import org.voxis.dictation.client.DictationState
import org.voxis.dictation.engine.SpeechModel

class MainActivity : ComponentActivity() {

    private lateinit var dictationClient: DictationClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dictationClient = DictationClient.builder(this)
            .setModel(SpeechModel.TinyEnglish)
            .setContinuousMode(true)
            .setAutoPunctuate(true)
            .setAutoCapitalize(true)
            .build()

        setContent {
            VoxisApp(client = dictationClient)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dictationClient.release()
    }
}

enum class NavTab(val title: String) {
    STUDIO("Studio"),
    MODELS("Models"),
    SYSTEM("System Setup"),
    SDK("SDK Playground")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxisApp(client: DictationClient) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(NavTab.STUDIO) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Microphone permission is required for live voice dictation", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        containerColor = Color(0xFF0B0F19),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "VOXIS",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            color = Color.White,
                            fontSize = 20.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "DE-GOOGLED STT",
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            color = Color(0xFF10B981),
                            modifier = Modifier
                                .background(Color(0xFF10B981).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0F172A),
                tonalElevation = 4.dp
            ) {
                NavTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            when (tab) {
                                NavTab.STUDIO -> Icon(Icons.Default.Mic, contentDescription = tab.title)
                                NavTab.MODELS -> Icon(Icons.Default.Layers, contentDescription = tab.title)
                                NavTab.SYSTEM -> Icon(Icons.Default.SettingsVoice, contentDescription = tab.title)
                                NavTab.SDK -> Icon(Icons.Default.Code, contentDescription = tab.title)
                            }
                        },
                        label = { Text(tab.title, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF10B981),
                            selectedTextColor = Color(0xFF10B981),
                            unselectedIconColor = Color(0xFF64748B),
                            unselectedTextColor = Color(0xFF64748B),
                            indicatorColor = Color(0xFF1E293B)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                NavTab.STUDIO -> StudioScreen(client, hasMicPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                NavTab.MODELS -> ModelsScreen()
                NavTab.SYSTEM -> SystemSetupScreen()
                NavTab.SDK -> SdkPlaygroundScreen()
            }
        }
    }
}

@Composable
fun StudioScreen(
    client: DictationClient,
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current
    val state by client.state.collectAsState()
    val audioLevel by client.audioLevels.collectAsState()
    var transcriptText by remember { mutableStateOf("") }

    val isListening = state is DictationState.Listening || state is DictationState.Transcribing

    // Update transcript reactively from state changes
    LaunchedEffect(state) {
        when (val s = state) {
            is DictationState.Transcribing -> {
                if (s.partialText.isNotBlank()) {
                    transcriptText = s.partialText
                }
            }
            is DictationState.Complete -> {
                if (s.finalText.isNotBlank()) {
                    transcriptText = s.finalText
                }
            }
            else -> {}
        }
    }

    // Also collect from transcription stream
    LaunchedEffect(client) {
        client.transcriptions.collect { result ->
            if (result.text.isNotBlank()) {
                transcriptText = result.text
            }
        }
    }

    DisposableEffect(client) {
        val listener = object : DictationListener {
            override fun onStateChanged(s: DictationState) {}
            override fun onPartialTranscription(text: String) {
                if (text.isNotBlank()) transcriptText = text
            }
            override fun onFinalTranscription(text: String) {
                if (text.isNotBlank()) transcriptText = text
            }
            override fun onAudioLevel(normalizedLevel: Float) {}
            override fun onError(error: String, cause: Throwable?) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            }
        }
        client.addListener(listener)
        onDispose {
            client.removeListener(listener)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusDotColor by animateColorAsState(
                        targetValue = if (isListening) Color(0xFF10B981) else Color(0xFF64748B)
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (state) {
                            is DictationState.Idle -> "READY"
                            is DictationState.Preparing -> "PREPARING..."
                            is DictationState.Listening -> "LISTENING..."
                            is DictationState.Transcribing -> "TRANSCRIBING..."
                            is DictationState.Complete -> "COMPLETE"
                            is DictationState.Error -> "ERROR"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Text(
                    text = "100% OFFLINE (AOSP)",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Live Audio Waveform Bars
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val barsCount = 24
                    for (i in 0 until barsCount) {
                        val factor = if (isListening) {
                            ((audioLevel * ((i % 5 + 1) / 5f)).coerceIn(0.08f, 1f))
                        } else 0.05f

                        val barHeight by animateFloatAsState(targetValue = 48.dp.value * factor)

                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(barHeight.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (isListening) Color(0xFF10B981) else Color(0xFF334155)
                                )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Main Transcription Canvas
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE TRANSCRIPT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "${transcriptText.split("\\s+".toRegex()).filter { it.isNotBlank() }.size} words",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (transcriptText.isBlank()) {
                        Column {
                            Text(
                                text = "Tap the microphone below to begin offline speech dictation...",
                                color = Color(0xFF64748B),
                                fontSize = 16.sp,
                                lineHeight = 24.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "You can speak naturally. Spoken punctuation ('period', 'comma', 'question mark') and sentence capitalization are formatted automatically.",
                                color = Color(0xFF475569),
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Text(
                            text = transcriptText,
                            color = Color.White,
                            fontSize = 18.sp,
                            lineHeight = 26.sp
                        )
                    }
                }

                // Quick Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Test Speech Injection button for instant emulator testing
                    TextButton(
                        onClick = {
                            val samplePhrases = listOf(
                                "Hello, this is Voxis offline speech recognition running on device.",
                                "Dictation test with automatic punctuation, comma, and capitalization.",
                                "Completely de-googled voice typing with zero cloud dependencies period."
                            )
                            val phrase = samplePhrases.random()
                            client.injectTranscribedText(phrase)
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Test Speech", tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test Speech", fontSize = 12.sp, color = Color(0xFF10B981))
                    }

                    Row {
                        IconButton(
                            onClick = {
                                if (transcriptText.isNotBlank()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Voxis Transcript", transcriptText))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF94A3B8))
                        }

                        IconButton(
                            onClick = {
                                if (transcriptText.isNotBlank()) {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, transcriptText)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Dictation"))
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color(0xFF94A3B8))
                        }

                        IconButton(
                            onClick = {
                                transcriptText = ""
                            }
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear", tint = Color(0xFF94A3B8))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Large Dictation Trigger Button
        val buttonScale by animateFloatAsState(targetValue = if (isListening) 1.08f else 1f)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .scale(buttonScale)
                .clip(CircleShape)
                .background(if (isListening) Color(0xFF10B981) else Color(0xFF1E293B))
                .border(2.dp, if (isListening) Color(0xFF34D399) else Color(0xFF334155), CircleShape)
                .clickable {
                    if (!hasMicPermission) {
                        onRequestPermission()
                        return@clickable
                    }

                    if (isListening) {
                        client.stopListening()
                    } else {
                        client.startListening()
                    }
                }
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = "Dictation Toggle",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isListening) "Tap to Stop" else "Tap to Dictate",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp
        )
    }
}

@Composable
fun ModelsScreen() {
    val models = remember { SpeechModel.ALL }
    var activeModelId by remember { mutableStateOf(SpeechModel.TinyEnglish.id) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Offline Whisper Models",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Quantized on-device speech models running locally on CPU/NNAPI without Google Play Services.",
            fontSize = 13.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        models.forEach { model ->
            val isCurrent = model.id == activeModelId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable {
                        activeModelId = model.id
                        Toast.makeText(context, "${model.displayName} selected", Toast.LENGTH_SHORT).show()
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isCurrent) Color(0xFF1E293B) else Color(0xFF151E2E)
                ),
                border = if (isCurrent) CardDefaults.outlinedCardBorder().copy(width = 1.dp, brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF10B981))) else null,
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = model.displayName,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "${model.approximateSizeMb} MB",
                            color = Color(0xFF10B981),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = model.description,
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isCurrent) "ACTIVE ENGINE" else if (model.isBundled) "INSTALLED" else "AVAILABLE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrent) Color(0xFF10B981) else Color(0xFF64748B)
                        )

                        Button(
                            onClick = {
                                activeModelId = model.id
                                Toast.makeText(context, "${model.displayName} selected", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCurrent) Color(0xFF10B981) else Color(0xFF334155)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isCurrent) "Selected" else "Select", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SystemSetupScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "De-Googled Voice Setup",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Replace Google Voice Typing with Voxis system-wide across GrapheneOS, CalyxOS, LineageOS, or AOSP.",
            fontSize = 13.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        val steps = listOf(
            "1. Open Android System Settings" to "Navigate to Settings on your device.",
            "2. Go to Languages & Input" to "Select System -> Languages & Input -> Voice Input service.",
            "3. Choose 'Voxis Offline Speech'" to "Select Voxis as the primary voice recognition provider.",
            "4. Enjoy In Any Keyboard" to "Open HeliBoard, FlorisBoard, OpenBoard, or any app and tap the mic button!"
        )

        steps.forEach { (title, description) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(description, color = Color(0xFF94A3B8), fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "AOSP Service Status",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "• Service: org.voxis.dictation.service.VoxisRecognitionService\n• Permission: android.permission.BIND_RECOGNITION_SERVICE\n• Offline Guarantee: Zero network calls, zero GMS telemetry",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun SdkPlaygroundScreen() {
    val sampleCode = """
// 1. Build Client in any Android App
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
        is DictationState.Error -> showError(state.message)
    }
}

// 3. Start / Stop Recording
dictationClient.startListening()
dictationClient.stopListening()
""".trimIndent()

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Embeddable SDK Sandbox",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Easily embed Voxis into any existing or new Android app with the :dictation-sdk module.",
            fontSize = 13.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "KOTLIN INTEGRATION SNIPPET",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Voxis SDK Code", sampleCode))
                            Toast.makeText(context, "Snippet copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = Color(0xFF94A3B8))
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = sampleCode,
                    color = Color(0xFFE2E8F0),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            }
        }
    }
}
