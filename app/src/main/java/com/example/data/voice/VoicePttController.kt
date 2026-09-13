package com.example.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
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

    private var toneGenerator: ToneGenerator? = null
    private var audioRecord: AudioRecord? = null
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
        if (wasTransmitting) {
            playChirpEnd()
        }
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
                val bufferSize = maxOf(minBufferSize, 2048)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                    val buffer = ShortArray(bufferSize / 2)
                    while (isActive && _pttState.value == PttState.TRANSMITTING) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            var sum = 0.0
                            for (i in 0 until read) {
                                sum += buffer[i] * buffer[i]
                            }
                            val rms = sqrt(sum / read)
                            val normalized = (rms / 25000.0).toFloat().coerceIn(0.05f, 1f)
                            _audioAmplitude.value = normalized
                        }
                        delay(40)
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
        scope.cancel()
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
    }
}
