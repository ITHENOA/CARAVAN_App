package com.example.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

enum class PttState {
    IDLE,
    REQUESTING,
    TRANSMITTING,
    BUSY
}

class VoicePttController(private val context: Context) {
    private val _pttState = MutableStateFlow(PttState.IDLE)
    val pttState: StateFlow<PttState> = _pttState.asStateFlow()

    private val _activeSpeakerName = MutableStateFlow<String?>(null)
    val activeSpeakerName: StateFlow<String?> = _activeSpeakerName.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    // Callback invoked when a chunk of raw 16-bit PCM audio is captured from the mic
    var onAudioChunkCaptured: ((ByteArray) -> Unit)? = null

    private var toneGenerator: ToneGenerator? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: Exception) {}
    }

    fun playChirpStart() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
        } catch (_: Exception) {}
        vibrate(80)
    }

    fun playChirpEnd() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 90)
        } catch (_: Exception) {}
        vibrate(50)
    }

    fun playFloorAlert() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 100)
        } catch (_: Exception) {}
        vibrate(60)
    }

    fun onRequestPtt() {
        if (_pttState.value == PttState.BUSY) return
        _pttState.value = PttState.REQUESTING
    }

    fun onFloorGranted() {
        _pttState.value = PttState.TRANSMITTING
        _activeSpeakerName.value = "You"
        playChirpStart()
        startAudioCapture()
    }

    fun onFloorBusy(speakerName: String) {
        _pttState.value = PttState.BUSY
        _activeSpeakerName.value = speakerName
        playFloorAlert()
        stopAudioCapture()
    }

    fun onFloorReleased() {
        val wasTransmitting = _pttState.value == PttState.TRANSMITTING
        _pttState.value = PttState.IDLE
        _activeSpeakerName.value = null
        stopAudioCapture()
        cleanUpAudioTrack()
        if (wasTransmitting) {
            playChirpEnd()
        }
    }

    /**
     * Streams incoming raw PCM 16-bit mono audio data from another convoy member directly through AudioTrack.
     */
    @Synchronized
    fun playAudioChunk(pcmData: ByteArray, sampleRate: Int = 16000) {
        if (pcmData.isEmpty()) return
        try {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                initAudioTrack(sampleRate)
            }
            audioTrack?.let { track ->
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                track.write(pcmData, 0, pcmData.size)
            }
        } catch (e: Exception) {
            Log.e("VoicePttController", "Failed to play audio chunk", e)
        }
    }

    private fun initAudioTrack(sampleRate: Int) {
        cleanUpAudioTrack()
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize * 2, 8192)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e("VoicePttController", "AudioTrack init error", e)
        }
    }

    private fun cleanUpAudioTrack() {
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        audioTrack = null
    }

    private fun startAudioCapture() {
        stopAudioCapture()
        recordingJob = scope.launch {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.w("VoicePttController", "RECORD_AUDIO permission not granted")
                    return@launch
                }

                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                // Chunks of ~128ms (2048 shorts = 4096 bytes)
                val bufferSize = maxOf(minBufferSize, 4096)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                    val buffer = ShortArray(2048) // 128ms per chunk
                    while (isActive && _pttState.value == PttState.TRANSMITTING) {
                        val readShorts = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readShorts > 0) {
                            // Compute RMS for UI visual wave animation
                            var sum = 0.0
                            for (i in 0 until readShorts) {
                                val s = buffer[i]
                                sum += s * s
                            }
                            val rms = sqrt(sum / readShorts)
                            val normalized = (rms / 25000.0).toFloat().coerceIn(0.05f, 1f)
                            _audioAmplitude.value = normalized

                            // Convert 16-bit PCM ShortArray to Little-Endian ByteArray
                            val byteData = ByteArray(readShorts * 2)
                            var idx = 0
                            for (i in 0 until readShorts) {
                                val s = buffer[i].toInt()
                                byteData[idx++] = (s and 0xFF).toByte()
                                byteData[idx++] = ((s shr 8) and 0xFF).toByte()
                            }

                            // Transmit live audio chunk over WebSocket network
                            onAudioChunkCaptured?.invoke(byteData)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VoicePttController", "Audio recording error", e)
            } finally {
                cleanUpAudioRecord()
                _audioAmplitude.value = 0f
            }
        }
    }

    private fun stopAudioCapture() {
        recordingJob?.cancel()
        recordingJob = null
        cleanUpAudioRecord()
        _audioAmplitude.value = 0f
    }

    private fun cleanUpAudioRecord() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }

    fun release() {
        stopAudioCapture()
        cleanUpAudioTrack()
        scope.cancel()
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
    }
}
