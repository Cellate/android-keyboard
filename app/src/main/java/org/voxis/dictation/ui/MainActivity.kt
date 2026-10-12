package org.voxis.dictation.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.voxis.dictation.client.DictationClient
import org.voxis.dictation.client.DictationListener
import org.voxis.dictation.client.DictationState
import org.voxis.dictation.engine.ModelManager
import org.voxis.dictation.engine.SpeechModel
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var dictationClient: DictationClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activeModel = ModelManager.getActiveModel(this)

        dictationClient = DictationClient.builder(this)
            .setModel(activeModel)
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
                NavTab.STUDIO -> StudioScreen(
                    client = client,
                    hasMicPermission = hasMicPermission,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onNavigateToModels = { selectedTab = NavTab.MODELS }
                )
                NavTab.MODELS -> ModelsScreen(client = client)
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
    onRequestPermission: () -> Unit,
    onNavigateToModels: () -> Unit
) {
    val context = LocalContext.current
    val state by client.state.collectAsState()
    val audioLevel by client.audioLevels.collectAsState()
    var transcriptText by remember { mutableStateOf("") }

    val activeModel = client.currentModel
    val isModelDownloaded = activeModel.isDownloaded()

    val isListening = state is DictationState.Listening || state is DictationState.Transcribing

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
        // Active Model & Status Bar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToModels() },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MODEL: ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = activeModel.displayName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF10B981)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "[${activeModel.language}]",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Text(
                        text = if (isModelDownloaded) "● LOADED" else "⚠️ NOT DOWNLOADED",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isModelDownloaded) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }
            }
        }

        // Warning banner if model file not yet downloaded
        if (!isModelDownloaded) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToModels() },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2012)),
                border = CardDefaults.outlinedCardBorder().copy(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFF59E0B))
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model file not downloaded yet",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFCD34D)
                        )
                        Text(
                            text = "Tap to download Hinglish/English/Hindi model weights or import from storage.",
                            fontSize = 11.sp,
                            color = Color(0xFFFDE68A)
                        )
                    }
                    Text(
                        text = "GET →",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFCD34D)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Live Audio Waveform Bars
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val barsCount = 24
                    for (i in 0 until barsCount) {
                        val factor = if (isListening) {
                            ((audioLevel * ((i % 5 + 1) / 5f)).coerceIn(0.08f, 1f))
                        } else 0.05f

                        val barHeight by animateFloatAsState(targetValue = 42.dp.value * factor)

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

        Spacer(Modifier.height(8.dp))

        // Hinglish Real-Time Latency Mode Selector
        var selectedHinglishMode by remember { mutableStateOf("HYBRID") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "HYBRID" to "⚡ Hybrid (Real-Time)",
                "STREAMING" to "⚡ Fast Stream (<100ms)",
                "VAD" to "🎯 Whisper VAD"
            ).forEach { (modeKey, label) ->
                val isSelected = selectedHinglishMode == modeKey
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0xFF10B981) else Color(0xFF1E293B),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedHinglishMode = modeKey }
                ) {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.Black else Color(0xFF94A3B8),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

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
                                text = if (isListening) "Listening... speak into your microphone..." else "Tap the microphone below to begin speech dictation...",
                                color = if (isListening) Color(0xFF10B981) else Color(0xFF64748B),
                                fontSize = 16.sp,
                                lineHeight = 24.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Active Mode: ${activeModel.language}. Words spoken will be recognized directly and rendered in real time.",
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
                    // Test speech injection clearly labeled for simulated testing
                    TextButton(
                        onClick = {
                            val samplePhrases = when (activeModel.languageCode) {
                                "hi-en" -> listOf(
                                    "Haan bhai, kal meeting schedule kar do at 10 AM.",
                                    "Yeh offline voice dictation bahut smoothly kaam kar raha hai.",
                                    "Please check karo and mujhe update do, thank you."
                                )
                                "hi" -> listOf(
                                    "नमस्ते, यह एक ऑफ़लाइन डिक्टेशन परीक्षण है।",
                                    "ध्वनि पहचान बिना इंटरनेट के सुचारू रूप से कार्य कर रही है।",
                                    "कृपया इस संदेश को सुरक्षित रखें।"
                                )
                                else -> listOf(
                                    "Hello, this is Voxis offline speech recognition.",
                                    "Offline voice typing with zero cloud dependencies.",
                                    "Real time speech to text transcription on device."
                                )
                            }
                            val phrase = samplePhrases.random()
                            client.injectTranscribedText(phrase)
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Test Sample", tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sample Injection (Test)", fontSize = 11.sp, color = Color(0xFF10B981))
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
fun ModelsScreen(client: DictationClient) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var availableModels by remember { mutableStateOf(ModelManager.getAvailableModels(context)) }
    var activeModel by remember { mutableStateOf(client.currentModel) }
    var selectedFilter by remember { mutableStateOf("ALL") }

    // Download state tracking
    var downloadingModelId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadBytesText by remember { mutableStateOf("") }
    var currentDownloadJob by remember { mutableStateOf<Job?>(null) }

    // Import Dialog state
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var importLanguage by remember { mutableStateOf("Hinglish (Code-Mixed)") }

    fun refreshModels() {
        availableModels = ModelManager.getAvailableModels(context)
        activeModel = ModelManager.getActiveModel(context)
        client.updateModel(activeModel)
    }

    // SAF Document Picker for local downloaded models
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    if (showImportDialog && pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                pendingImportUri = null
            },
            title = { Text("Import Local Model File", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Select the target language mode for this model:",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    listOf("Hinglish (Code-Mixed)", "English", "Hindi (हिन्दी)").forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { importLanguage = lang }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = importLanguage == lang,
                                onClick = { importLanguage = lang },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF10B981))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(lang, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingImportUri ?: return@Button
                        showImportDialog = false
                        coroutineScope.launch {
                            Toast.makeText(context, "Importing model...", Toast.LENGTH_SHORT).show()
                            val result = ModelManager.importModelFromUri(
                                context = context,
                                uri = uri,
                                fileNameHint = null,
                                targetLanguage = importLanguage
                            )
                            if (result.isSuccess) {
                                val imported = result.getOrNull()
                                Toast.makeText(context, "Imported ${imported?.displayName} successfully!", Toast.LENGTH_LONG).show()
                                refreshModels()
                            } else {
                                Toast.makeText(context, "Import failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                            pendingImportUri = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Import & Set Active", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    pendingImportUri = null
                }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Speech Models & Weights",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Download or import local offline models for Hinglish (code-mixed), English, and Hindi.",
            fontSize = 13.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        // Storage Picker Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    documentPickerLauncher.launch(arrayOf("*/*"))
                },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
            border = CardDefaults.outlinedCardBorder().copy(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF3B82F6))
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1E3A8A)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.FileOpen,
                        contentDescription = "Import",
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Import Model from Phone Storage",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Select any local .bin, .gguf, .onnx, or .zip model file on your phone.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Open",
                    tint = Color(0xFF60A5FA)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Language Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL" to "All", "HINGLISH" to "Hinglish", "ENGLISH" to "English", "HINDI" to "Hindi").forEach { (filterKey, label) ->
                val isSelected = selectedFilter == filterKey
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = filterKey },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF10B981),
                        selectedLabelColor = Color.Black,
                        containerColor = Color(0xFF1E293B),
                        labelColor = Color(0xFF94A3B8)
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Filter models
        val filteredModels = availableModels.filter { model ->
            when (selectedFilter) {
                "HINGLISH" -> model.language.contains("Hinglish", ignoreCase = true)
                "ENGLISH" -> model.language.equals("English", ignoreCase = true)
                "HINDI" -> model.language.contains("Hindi", ignoreCase = true)
                else -> true
            }
        }

        filteredModels.forEach { model ->
            val isCurrent = model.id == activeModel.id
            val isDownloaded = model.isDownloaded()
            val isDownloading = downloadingModelId == model.id

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCurrent) Color(0xFF1E293B) else Color(0xFF151E2E)
                ),
                border = if (isCurrent) CardDefaults.outlinedCardBorder().copy(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF10B981))
                ) else null,
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = model.language,
                                color = Color(0xFF10B981),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = "${model.approximateSizeMb} MB",
                            color = Color(0xFFE2E8F0),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = model.description,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    if (isDownloaded && !model.localPath.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Path: ${File(model.localPath!!).name}",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Progress bar if downloading
                    if (isDownloading) {
                        Spacer(Modifier.height(10.dp))
                        Column {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF10B981),
                                trackColor = Color(0xFF334155)
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Downloading: ${(downloadProgress * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    color = Color(0xFF10B981)
                                )
                                Text(
                                    text = downloadBytesText,
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isDownloaded) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = {
                                        ModelManager.setActiveModel(context, model.id)
                                        refreshModels()
                                        Toast.makeText(context, "${model.displayName} activated", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isCurrent) Color(0xFF10B981) else Color(0xFF334155)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (isCurrent) "✓ Active Model" else "Set Active",
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                IconButton(
                                    onClick = {
                                        ModelManager.deleteModel(context, model)
                                        refreshModels()
                                        Toast.makeText(context, "Deleted ${model.displayName}", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Model",
                                        tint = Color(0xFFEF4444)
                                    )
                                }
                            }
                        } else if (isDownloading) {
                            Button(
                                onClick = {
                                    currentDownloadJob?.cancel()
                                    downloadingModelId = null
                                    Toast.makeText(context, "Download cancelled", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Cancel", fontSize = 12.sp, color = Color.White)
                            }
                        } else {
                            // Model NOT downloaded: show in-app download, copy link, and open browser
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (downloadingModelId != null) {
                                            Toast.makeText(context, "Another download is in progress", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        downloadingModelId = model.id
                                        downloadProgress = 0f
                                        downloadBytesText = "Connecting..."

                                        currentDownloadJob = coroutineScope.launch {
                                            val result = ModelManager.downloadModel(
                                                context = context,
                                                model = model,
                                                onProgress = { progress, downloaded, total ->
                                                    downloadProgress = progress
                                                    val downMb = (downloaded.toDouble() / (1024 * 1024)).let { "%.1f".format(it) }
                                                    val totMb = (total.toDouble() / (1024 * 1024)).let { "%.1f".format(it) }
                                                    downloadBytesText = "$downMb / $totMb MB"
                                                }
                                            )
                                            downloadingModelId = null
                                            if (result.isSuccess) {
                                                Toast.makeText(context, "Downloaded ${model.displayName} successfully!", Toast.LENGTH_LONG).show()
                                                ModelManager.setActiveModel(context, model.id)
                                                refreshModels()
                                            } else {
                                                Toast.makeText(context, "Download failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Download", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Download", fontSize = 12.sp, color = Color.White)
                                }

                                Row {
                                    if (model.downloadUrl.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Model URL", model.downloadUrl))
                                                Toast.makeText(context, "Download link copied to clipboard!", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Link,
                                                contentDescription = "Copy Link",
                                                tint = Color(0xFF94A3B8)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(model.downloadUrl))
                                                context.startActivity(intent)
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.OpenInBrowser,
                                                contentDescription = "Open in Browser",
                                                tint = Color(0xFF94A3B8)
                                            )
                                        }
                                    }
                                }
                            }
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
                    text = "• Service: org.voxis.dictation.service.VoxisRecognitionService\n• Permission: android.permission.BIND_RECOGNITION_SERVICE\n• Offline Guarantee: Zero network calls during dictation, 100% on-device speech processing",
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
// 1. Build Client in any Android App with Active Model
val activeModel = ModelManager.getActiveModel(context)
val dictationClient = DictationClient.builder(context)
    .setModel(activeModel)
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
