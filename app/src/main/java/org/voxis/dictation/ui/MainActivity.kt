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
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.voxis.dictation.client.DictationClient
import org.voxis.dictation.client.DictationListener
import org.voxis.dictation.client.DictationState
import org.voxis.dictation.engine.DecodingStrategy
import org.voxis.dictation.engine.HinglishEngineMode
import org.voxis.dictation.engine.ModelManager
import org.voxis.dictation.engine.SpeechModel
import org.voxis.dictation.engine.VadSensitivity
import org.voxis.dictation.engine.VoiceModelConfig
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
fun VoxisApp(
    client: DictationClient,
    viewModel: TranscriptionViewModel = viewModel()
) {
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
                    viewModel = viewModel,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    viewModel: TranscriptionViewModel,
    client: DictationClient,
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit,
    onNavigateToModels: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var showConfigDialog by remember { mutableStateOf(false) }

    if (showConfigDialog) {
        VoiceModelConfigDialog(
            currentConfig = uiState.modelConfig,
            onDismiss = { showConfigDialog = false },
            onSave = { updated ->
                viewModel.updateModelConfig(updated)
                showConfigDialog = false
                Toast.makeText(context, "Voice model settings applied", Toast.LENGTH_SHORT).show()
            },
            onResetToRecommended = {
                viewModel.resetModelConfigToRecommended()
                Toast.makeText(context, "Reset to recommended settings", Toast.LENGTH_SHORT).show()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Active Model & Status Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onNavigateToModels() }
                    ) {
                        val statusDotColor by animateColorAsState(
                            targetValue = if (uiState.isRecording) Color(0xFF10B981) else Color(0xFF64748B)
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = uiState.pipelineStage.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Quick Action to Model & Audio Tuning Dialog
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.15f),
                            modifier = Modifier.clickable { showConfigDialog = true }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = "Parameters",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "PARAMETERS",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "100% OFFLINE",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onNavigateToModels() }
                    ) {
                        Text(
                            text = "ENGINE: ",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = uiState.pipelineMetrics.activeEngineDescription,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF10B981)
                        )
                    }

                    Text(
                        text = if (uiState.isModelLoaded) "● MODEL LOADED" else "● OFFLINE ENGINE READY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Dual Dictation Mode Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val isBatch = uiState.engineMode == HinglishEngineMode.RECORD_THEN_PROCESS
            val isLive = uiState.engineMode == HinglishEngineMode.LIVE_REALTIME

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isBatch) Color(0xFF10B981) else Color(0xFF1E293B),
                modifier = Modifier
                    .weight(1f)
                    .clickable { viewModel.setEngineMode(HinglishEngineMode.RECORD_THEN_PROCESS) }
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Record → Process (Batch)",
                        fontSize = 12.sp,
                        fontWeight = if (isBatch) FontWeight.Bold else FontWeight.Medium,
                        color = if (isBatch) Color.Black else Color.White
                    )
                    Text(
                        text = "Tap to speak, tap to stop & decode",
                        fontSize = 9.sp,
                        color = if (isBatch) Color(0xFF064E3B) else Color(0xFF94A3B8)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isLive) Color(0xFF10B981) else Color(0xFF1E293B),
                modifier = Modifier
                    .weight(1f)
                    .clickable { viewModel.setEngineMode(HinglishEngineMode.LIVE_REALTIME) }
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⚡ Live Real-Time",
                        fontSize = 12.sp,
                        fontWeight = if (isLive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isLive) Color.Black else Color.White
                    )
                    Text(
                        text = "Sub-100ms streaming words",
                        fontSize = 9.sp,
                        color = if (isLive) Color(0xFF064E3B) else Color(0xFF94A3B8)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Live Audio Waveform Bars
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.isRecording) "AUDIO INPUT (16kHz PCM)" else "AUDIO INPUT (STANDBY)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (uiState.isRecording) Color(0xFF10B981) else Color(0xFF64748B)
                    )
                    Text(
                        text = "${String.format("%.1f", uiState.currentDb)} dBFS",
                        fontSize = 10.sp,
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val barsCount = 28
                    for (i in 0 until barsCount) {
                        val factor = if (uiState.isRecording) {
                            ((uiState.audioLevel * ((i % 5 + 1) / 5f)).coerceIn(0.08f, 1f))
                        } else 0.05f

                        val barHeight by animateFloatAsState(targetValue = 36.dp.value * factor)

                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(barHeight.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (uiState.isRecording) Color(0xFF10B981) else Color(0xFF334155)
                                )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Behind-The-Scenes Pipeline Inspector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161F30)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BEHIND-THE-SCENES PIPELINE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF94A3B8)
                    )

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (uiState.pipelineStage) {
                            PipelineStage.RECORDING -> Color(0xFF047857)
                            PipelineStage.INFERENCE_RUNNING, PipelineStage.DISPATCHING_MODEL -> Color(0xFFB45309)
                            PipelineStage.COMPLETED -> Color(0xFF0F766E)
                            PipelineStage.ERROR -> Color(0xFFB91C1C)
                            else -> Color(0xFF334155)
                        }
                    ) {
                        Text(
                            text = uiState.pipelineStage.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Audio Buffer", fontSize = 9.sp, color = Color(0xFF64748B))
                        Text(
                            text = "${uiState.pipelineMetrics.sampleCount} samples (${String.format("%.1fs", uiState.pipelineMetrics.audioDurationSeconds)})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                    Column {
                        Text("Inference Latency", fontSize = 9.sp, color = Color(0xFF64748B))
                        Text(
                            text = if (uiState.pipelineMetrics.inferenceTimeMs > 0) "${uiState.pipelineMetrics.inferenceTimeMs}ms" else "--",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                    Column {
                        Text("VAD Pause", fontSize = 9.sp, color = Color(0xFF64748B))
                        Text(
                            text = "${uiState.modelConfig.vadSilenceEndpointMs}ms",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFCD34D)
                        )
                    }
                    Column {
                        Text("Threads", fontSize = 9.sp, color = Color(0xFF64748B))
                        Text(
                            text = "${uiState.modelConfig.nThreads} CPU",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF10B981)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Main Transcription Canvas
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (uiState.engineMode == HinglishEngineMode.LIVE_REALTIME) "LIVE STREAMING TRANSCRIPT" else "BATCH DICTATION OUTPUT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "${uiState.pipelineMetrics.wordCount} words",
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
                    val displayText = if (uiState.liveTranscript.isNotBlank()) {
                        uiState.liveTranscript
                    } else if (uiState.interimStreamingText.isNotBlank()) {
                        uiState.interimStreamingText
                    } else ""

                    if (displayText.isBlank()) {
                        Column {
                            Text(
                                text = if (uiState.isRecording) {
                                    if (uiState.engineMode == HinglishEngineMode.RECORD_THEN_PROCESS)
                                        "Listening and buffering audio... tap the microphone when finished to process with model."
                                    else
                                        "Listening... words will appear live in real-time as you speak..."
                                } else {
                                    "Tap the microphone button below to start voice dictation..."
                                },
                                color = if (uiState.isRecording) Color(0xFF10B981) else Color(0xFF64748B),
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Configured: ${uiState.modelConfig.languageCode.uppercase()} • ${uiState.modelConfig.nThreads} Threads • Auto-Punctuate: ${if (uiState.modelConfig.autoPunctuate) "ON" else "OFF"}",
                                color = Color(0xFF475569),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Text(
                            text = displayText,
                            color = Color.White,
                            fontSize = 17.sp,
                            lineHeight = 25.sp
                        )
                    }
                }

                // Quick Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val samplePhrases = when (uiState.modelConfig.languageCode) {
                                "hi" -> listOf(
                                    "नमस्ते, यह एक ऑफ़लाइन डिक्टेशन परीक्षण है।",
                                    "ध्वनि पहचान बिना इंटरनेट के सुचारू रूप से कार्य कर रही है।"
                                )
                                "en" -> listOf(
                                    "Hello, this is Voxis offline speech recognition.",
                                    "Real time speech to text transcription on device."
                                )
                                else -> listOf(
                                    "Haan bhai, kal meeting schedule kar do at 10 AM.",
                                    "Yeh offline voice dictation smoothly kaam kar raha hai."
                                )
                            }
                            client.injectTranscribedText(samplePhrases.random())
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Sample", tint = Color(0xFF10B981), modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sample Injection", fontSize = 11.sp, color = Color(0xFF10B981))
                    }

                    Row {
                        IconButton(
                            onClick = {
                                if (uiState.liveTranscript.isNotBlank()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Voxis Transcript", uiState.liveTranscript))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF94A3B8))
                        }

                        IconButton(
                            onClick = {
                                if (uiState.liveTranscript.isNotBlank()) {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, uiState.liveTranscript)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share Dictation"))
                                }
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color(0xFF94A3B8))
                        }

                        IconButton(
                            onClick = { viewModel.clearTranscript() }
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear", tint = Color(0xFF94A3B8))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Large Dictation Trigger Button
        val buttonScale by animateFloatAsState(targetValue = if (uiState.isRecording) 1.08f else 1f)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(74.dp)
                .scale(buttonScale)
                .clip(CircleShape)
                .background(if (uiState.isRecording) Color(0xFF10B981) else Color(0xFF1E293B))
                .border(2.dp, if (uiState.isRecording) Color(0xFF34D399) else Color(0xFF334155), CircleShape)
                .clickable {
                    if (!hasMicPermission) {
                        onRequestPermission()
                        return@clickable
                    }

                    if (uiState.isRecording) {
                        viewModel.stopTranscription()
                    } else {
                        viewModel.startTranscription()
                    }
                }
        ) {
            Icon(
                imageVector = if (uiState.isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = "Dictation Toggle",
                tint = Color.White,
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (uiState.isRecording) {
                if (uiState.engineMode == HinglishEngineMode.RECORD_THEN_PROCESS) "Tap to Finish & Process" else "Tap to Stop"
            } else "Tap to Speak",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp
        )
    }
}

/**
 * Full Voice Model & Audio Parameters Configuration Dialog.
 * Pre-populated with verified recommended defaults for mobile on-device execution.
 */
@Composable
fun VoiceModelConfigDialog(
    currentConfig: VoiceModelConfig,
    onDismiss: () -> Unit,
    onSave: (VoiceModelConfig) -> Unit,
    onResetToRecommended: () -> Unit
) {
    var threads by remember { mutableStateOf(currentConfig.nThreads.toFloat()) }
    var silenceMs by remember { mutableStateOf(currentConfig.vadSilenceEndpointMs.toFloat()) }
    var language by remember { mutableStateOf(currentConfig.languageCode) }
    var prompt by remember { mutableStateOf(currentConfig.initialPrompt) }
    var autoPunctuate by remember { mutableStateOf(currentConfig.autoPunctuate) }
    var autoCapitalize by remember { mutableStateOf(currentConfig.autoCapitalize) }
    var decodingStrategy by remember { mutableStateOf(currentConfig.decodingStrategy) }
    var sensitivity by remember { mutableStateOf(currentConfig.vadSensitivity) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Voice Model Parameters",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Text(
                    text = "(Recommended Pre-Set)",
                    color = Color(0xFF10B981),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Configure on-device inference and audio processing. Recommended settings are marked for optimal mobile accuracy and speed.",
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8)
                )

                Spacer(Modifier.height(14.dp))

                // 1. CPU Inference Threads
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("CPU Inference Threads", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("${threads.toInt()} Threads ${if (threads.toInt() == 4) "(Recommended)" else ""}", fontSize = 12.sp, color = Color(0xFF10B981))
                }
                Slider(
                    value = threads,
                    onValueChange = { threads = it },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF10B981), activeTrackColor = Color(0xFF10B981))
                )

                Spacer(Modifier.height(10.dp))

                // 2. VAD Silence Endpoint Threshold
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("VAD Pause Silence Endpoint", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("${silenceMs.toInt()} ms ${if (silenceMs.toInt() == 280) "(Recommended)" else ""}", fontSize = 12.sp, color = Color(0xFF10B981))
                }
                Slider(
                    value = silenceMs,
                    onValueChange = { silenceMs = it },
                    valueRange = 150f..800f,
                    steps = 12,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF10B981), activeTrackColor = Color(0xFF10B981))
                )

                Spacer(Modifier.height(10.dp))

                // 3. Language & Dialect
                Text("Target Language Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "hi-en" to "Hinglish (Code-Mixed) (Recommended)",
                    "en" to "English (US / UK / Indian)",
                    "hi" to "Hindi (हिन्दी Native Script)"
                ).forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { language = code }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = language == code,
                            onClick = { language = code },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF10B981))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 12.sp, color = Color.White)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // 4. Initial Prompt Conditioning
                Text("Initial Prompt Conditioning", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("Biases decoding towards conversational vocabulary and punctuation.", fontSize = 10.sp, color = Color(0xFF64748B))
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF10B981),
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )

                Spacer(Modifier.height(10.dp))

                // 5. Post Processing Switches
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Auto-Punctuate", fontSize = 13.sp, color = Color.White)
                        Text("(Recommended: ON)", fontSize = 10.sp, color = Color(0xFF10B981))
                    }
                    Switch(
                        checked = autoPunctuate,
                        onCheckedChange = { autoPunctuate = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF10B981))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Auto-Capitalize", fontSize = 13.sp, color = Color.White)
                        Text("(Recommended: ON)", fontSize = 10.sp, color = Color(0xFF10B981))
                    }
                    Switch(
                        checked = autoCapitalize,
                        onCheckedChange = { autoCapitalize = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF10B981))
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        VoiceModelConfig(
                            nThreads = threads.toInt(),
                            vadSilenceEndpointMs = silenceMs.toLong(),
                            languageCode = language,
                            initialPrompt = prompt,
                            autoPunctuate = autoPunctuate,
                            autoCapitalize = autoCapitalize,
                            vadSensitivity = sensitivity,
                            decodingStrategy = decodingStrategy
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Text("Save & Apply", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        val rec = VoiceModelConfig.getRecommended()
                        threads = rec.nThreads.toFloat()
                        silenceMs = rec.vadSilenceEndpointMs.toFloat()
                        language = rec.languageCode
                        prompt = rec.initialPrompt
                        autoPunctuate = rec.autoPunctuate
                        autoCapitalize = rec.autoCapitalize
                        sensitivity = rec.vadSensitivity
                        decodingStrategy = rec.decodingStrategy
                        onResetToRecommended()
                    }
                ) {
                    Text("Reset Recommended", color = Color(0xFFFCD34D), fontSize = 12.sp)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            }
        },
        containerColor = Color(0xFF1E293B)
    )
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
