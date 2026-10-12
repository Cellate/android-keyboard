package org.voxis.dictation.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.voxis.dictation.client.DictationClient
import org.voxis.dictation.client.DictationState
import java.util.ArrayList

class VoiceInputActivity : ComponentActivity() {

    private lateinit var client: DictationClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        client = DictationClient.builder(this)
            .setContinuousMode(true)
            .setAutoPunctuate(true)
            .setAutoCapitalize(true)
            .build()

        client.startListening()

        setContent {
            val state by client.state.collectAsState()
            val audioLevel by client.audioLevels.collectAsState()
            var currentText by remember { mutableStateOf("") }

            DisposableEffect(Unit) {
                onDispose {
                    client.release()
                }
            }

            when (val s = state) {
                is DictationState.Transcribing -> currentText = s.partialText
                is DictationState.Complete -> {
                    currentText = s.finalText
                    returnResultAndFinish(currentText)
                }
                else -> {}
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF1E293B),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Voxis Voice Input",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Pulsing Mic Visualizer
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .scale(1f + (audioLevel * 0.25f))
                            .background(
                                color = if (state is DictationState.Listening || state is DictationState.Transcribing)
                                    Color(0xFF10B981)
                                else Color(0xFF475569),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Microphone",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (currentText.isBlank()) "Speak now..." else currentText,
                        color = if (currentText.isBlank()) Color(0xFF94A3B8) else Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = {
                                client.cancel()
                                finish()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                            Spacer(Modifier.size(4.dp))
                            Text("Cancel", color = Color.White)
                        }

                        Button(
                            onClick = {
                                client.stopListening()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Done")
                            Spacer(Modifier.size(4.dp))
                            Text("Done", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    private fun returnResultAndFinish(text: String) {
        val resultIntent = Intent().apply {
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS,
                ArrayList(listOf(text))
            )
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
