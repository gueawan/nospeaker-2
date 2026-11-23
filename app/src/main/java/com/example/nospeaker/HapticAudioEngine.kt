package com.example.nospeaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.audiofx.HapticGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

enum class HapticMode(val label: String, val desc: String) {
    ACOUSTIC_PULSE("Acoustic Pulse", "Resolusi cepat (4-8ms) untuk vokal & melodi lo-fi"),
    BASS_ENVELOPE("Bass Envelope", "Fokus energi ketukan & bass yang kuat"),
    NATIVE_GENERATOR("HapticGenerator", "API bawaan Android 12+ (jika didukung One UI)")
}

data class DeviceHapticInfo(
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val supportsAudioCoupled: Boolean,
    val supportsHapticGenerator: Boolean
)

class HapticAudioEngine(private val context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var playJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var hapticGenerator: HapticGenerator? = null

    fun inspectHardware(): DeviceHapticInfo {
        val audioCoupled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioManager.isHapticPlaybackSupported()
        } else false

        val generator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            HapticGenerator.isAvailable()
        } else false

        return DeviceHapticInfo(
            hasVibrator = vibrator.hasVibrator(),
            hasAmplitudeControl = vibrator.hasAmplitudeControl(),
            supportsAudioCoupled = audioCoupled,
            supportsHapticGenerator = generator
        )
    }

    fun start(
        uri: Uri,
        mode: HapticMode,
        gain: Float,
        frameMs: Int,
        onLevelUpdate: (Float) -> Unit,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()

        if (mode == HapticMode.NATIVE_GENERATOR) {
            startNativeHapticGenerator(uri, onFinished, onError)
            return
        }

        playJob = CoroutineScope(Dispatchers.Default).launch {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null

            try {
                extractor.setDataSource(context, uri, null)

                var audioTrackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        format = f
                        break
                    }
                }

                if (audioTrackIndex == -1 || format == null) {
                    withContext(Dispatchers.Main) {
                        onError("Track audio tidak ditemukan di file ini.")
                    }
                    return@launch
                }

                extractor.selectTrack(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                codec = MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }

                val stepMs = if (mode == HapticMode.BASS_ENVELOPE) {
                    frameMs.coerceIn(10, 25)
                } else {
                    frameMs.coerceIn(4, 12)
                }

                val samplesPerFrame = (sampleRate * stepMs / 1000).coerceAtLeast(1)
                val framesPerChunk = (120 / stepMs).coerceAtLeast(4)

                val timingChunk = ArrayList<Long>(framesPerChunk)
                val ampChunk = ArrayList<Int>(framesPerChunk)

                var sampleAccumSq = 0.0
                var samplePeak = 0
                var sampleCount = 0

                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false

                while (isActive && !sawOutputEOS) {
                    if (!sawInputEOS) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)

                        val shortBuf = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val totalShorts = shortBuf.remaining()
                        var i = 0

                        while (i < totalShorts && isActive) {
                            var mono = 0
                            for (ch in 0 until channelCount) {
                                if (i < totalShorts) {
                                    mono += shortBuf.get(i).toInt()
                                    i++
                                }
                            }
                            mono /= channelCount

                            val absVal = abs(mono)
                            if (absVal > samplePeak) samplePeak = absVal
                            sampleAccumSq += mono.toDouble() * mono.toDouble()
                            sampleCount++

                            if (sampleCount >= samplesPerFrame) {
                                val rms = sqrt(sampleAccumSq / sampleCount) / 32768.0
                                val peakNorm = samplePeak / 32768.0

                                val rawLevel = if (mode == HapticMode.ACOUSTIC_PULSE) {
                                    (peakNorm * 0.65 + rms * 0.35) * gain
                                } else {
                                    rms * gain * 1.4
                                }

                                val normalized = if (rawLevel < 0.04) 0f else rawLevel.toFloat().coerceIn(0f, 1f)
                                val amp = (normalized * 255).toInt().coerceIn(0, 255)

                                timingChunk.add(stepMs.toLong())
                                ampChunk.add(amp)

                                sampleAccumSq = 0.0
                                samplePeak = 0
                                sampleCount = 0

                                if (timingChunk.size >= framesPerChunk) {
                                    val chunkDurationMs = timingChunk.sum()
                                    val tArr = timingChunk.toLongArray()
                                    val aArr = ampChunk.toIntArray()

                                    val avgLevel = aArr.average().toFloat() / 255f
                                    withContext(Dispatchers.Main) {
                                        onLevelUpdate(avgLevel)
                                    }

                                    if (vibrator.hasAmplitudeControl()) {
                                        val effect = VibrationEffect.createWaveform(tArr, aArr, -1)
                                        vibrator.vibrate(effect)
                                    }

                                    timingChunk.clear()
                                    ampChunk.clear()
                                    delay(chunkDurationMs)
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Gagal memproses file audio.")
                }
            } finally {
                try {
                    codec?.stop()
                    codec?.release()
                } catch (_: Exception) {}
                extractor.release()
                vibrator.cancel()
                withContext(Dispatchers.Main) {
                    onLevelUpdate(0f)
                    onFinished()
                }
            }
        }
    }

    private fun startNativeHapticGenerator(
        uri: Uri,
        onFinished: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !HapticGenerator.isAvailable()) {
            onError("HapticGenerator tidak tersedia di perangkat ini. Gunakan mode Acoustic Pulse.")
            return
        }

        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setHapticChannelsMuted(false)
                .build()

            val player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(context, uri)
                prepare()
                setVolume(0.01f, 0.01f)
                setOnCompletionListener {
                    stop()
                    onFinished()
                }
            }

            val gen = HapticGenerator.create(player.audioSessionId)
            gen.enabled = true

            mediaPlayer = player
            hapticGenerator = gen
            player.start()
        } catch (e: Exception) {
            stop()
            onError("Gagal menjalankan HapticGenerator: ${e.message}")
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        vibrator.cancel()

        try {
            hapticGenerator?.enabled = false
            hapticGenerator?.release()
        } catch (_: Exception) {}
        hapticGenerator = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
