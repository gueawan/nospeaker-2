package com.example.nospeaker

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var engine: HapticAudioEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = HapticAudioEngine(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NoSpeakerScreen(engine = engine)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
    }
}

@Composable
fun NoSpeakerScreen(engine: HapticAudioEngine) {
    val context = LocalContext.current
    val hwInfo = remember { engine.inspectHardware() }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("Belum ada file dipilih") }
    var selectedMode by remember { mutableStateOf(HapticMode.ACOUSTIC_PULSE) }
    var gain by remember { mutableFloatStateOf(1.8f) }
    var frameMs by remember { mutableIntStateOf(6) }
    var isPlaying by remember { mutableStateOf(false) }
    var hapticLevel by remember { mutableFloatStateOf(0f) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            errorMsg = null
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            selectedFileName = cursor?.use {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (it.moveToFirst() && idx >= 0) it.getString(idx) else uri.lastPathSegment
            } ?: "Audio terpilih"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "NoSpeaker Android",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Putar MP3, WAV, M4A, atau OGG langsung lewat motor getar LRA.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Status Motor Getar", fontWeight = FontWeight.SemiBold)
                Text("Amplitude Control (LRA): ${if (hwInfo.hasAmplitudeControl) "Didukung" else "Tidak didukung"}")
                Text("Audio-Coupled Haptics: ${if (hwInfo.supportsAudioCoupled) "Didukung" else "Tidak terbuka di ROM"}")
                Text("HapticGenerator (API 31+): ${if (hwInfo.supportsHapticGenerator) "Tersedia" else "Tidak tersedia"}")
            }
        }

        OutlinedButton(
            onClick = { filePicker.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Pilih File Audio (MP3 / WAV / M4A)")
        }

        Text(
            text = "File: $selectedFileName",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        Text("Mode Konversi Getar", fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HapticMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = selectedMode == mode,
                        onClick = {
                            if (isPlaying) {
                                engine.stop()
                                isPlaying = false
                            }
                            selectedMode = mode
                        }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(mode.label, fontWeight = FontWeight.Medium)
                        Text(
                            mode.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (selectedMode != HapticMode.NATIVE_GENERATOR) {
            Column {
                Text("Penguat Getar (Gain): ${String.format(Locale.US, "%.1f", gain)}x")
                Slider(
                    value = gain,
                    onValueChange = { gain = it },
                    valueRange = 0.5f..4.0f
                )

                Text("Resolusi Pulsa: $frameMs ms (lebih kecil = lebih detail)")
                Slider(
                    value = frameMs.toFloat(),
                    onValueChange = { frameMs = it.roundToInt() },
                    valueRange = 4f..20f,
                    steps = 15
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Intensitas LRA Real-Time")
            LinearProgressIndicator(
                progress = { hapticLevel },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            )
        }

        if (errorMsg != null) {
            Text(
                text = errorMsg!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Button(
            onClick = {
                if (isPlaying) {
                    engine.stop()
                    isPlaying = false
                    hapticLevel = 0f
                } else {
                    val uri = selectedUri
                    if (uri == null) {
                        errorMsg = "Pilih file audio terlebih dahulu."
                        return@Button
                    }
                    errorMsg = null
                    isPlaying = true
                    engine.start(
                        uri = uri,
                        mode = selectedMode,
                        gain = gain,
                        frameMs = frameMs,
                        onLevelUpdate = { lvl -> hapticLevel = lvl },
                        onFinished = { isPlaying = false },
                        onError = { err ->
                            errorMsg = err
                            isPlaying = false
                        }
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(if (isPlaying) "Hentikan Getaran" else "Putar Lewat Motor Getar")
        }
    }
}
