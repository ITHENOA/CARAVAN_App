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
import java.io.ByteArrayOutputStream
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

    private val _voiceNoteRecording = MutableStateFlow(false)
    val voiceNoteRecording: StateFlow<Boolean> = _voiceNoteRecording.asStateFlow()

    /** ClientId of the remote speaker shown in the preview chip (name alone can mismatch). */
    private var activeSpeakerClientId: String? = null
    private var speakingClearJob: Job? = null

    // Callback invoked when a chunk of raw 16-bit PCM audio is captured from the mic
    var onAudioChunkCaptured: ((ByteArray) -> Unit)? = null
    /** Fired when voice-note hits max length so the VM can flush/send. */
    var onVoiceNoteAutoStop: (() -> Unit)? = null
    /** When false, PTT chirps stay silent (haptics still run). */
    var isSoundEnabled: () -> Boolean = { true }
    var isHapticsEnabled: () -> Boolean = { true }

    private var toneGenerator: ToneGenerator? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private var voiceNoteBuffer: ByteArrayOutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val SAMPLE_RATE = 16000
        /** Cap ~30s so buffered notes stay sendable over WS. */
        private const val MAX_VOICE_NOTE_BYTES = SAMPLE_RATE * 2 * 30
        /** Clear stuck "X is talking" if audio/ptt_release stops arriving. */
        private const val SPEAKING_IDLE_MS = 1_800L
    }

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: Exception) {}
    }

    fun playChirpStart() {
        if (isSoundEnabled()) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
            } catch (_: Exception) {}
        }
        if (isHapticsEnabled()) vibrate(80)
    }

    fun playChirpEnd() {
        if (isSoundEnabled()) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            } catch (_: Exception) {}
        }
        if (isHapticsEnabled()) vibrate(50)
    }

    fun playFloorAlert() {
        if (isSoundEnabled()) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 100)
            } catch (_: Exception) {}
        }
        if (isHapticsEnabled()) vibrate(60)
    }

    fun onRequestPtt() {
        if (_pttState.value == PttState.TRANSMITTING || _pttState.value == PttState.REQUESTING) return
        _pttState.value = PttState.REQUESTING
    }

    fun onFloorGranted() {
        // Instant open-mic: ignore duplicate grants while already live (no request queue).
        if (_pttState.value == PttState.TRANSMITTING) return
        if (_voiceNoteRecording.value) return
        speakingClearJob?.cancel()
        speakingClearJob = null
        activeSpeakerClientId = null
        _pttState.value = PttState.TRANSMITTING
        _activeSpeakerName.value = "You"
        playChirpStart()
        startAudioCapture(liveStream = true)
    }

    /** Hold/toggle voice note: record locally, then flush PCM via [stopVoiceNote]. */
    fun startVoiceNote() {
        if (_voiceNoteRecording.value) return
        if (_pttState.value == PttState.TRANSMITTING || _pttState.value == PttState.REQUESTING) return
        _voiceNoteRecording.value = true
        voiceNoteBuffer = ByteArrayOutputStream()
        playChirpStart()
        startAudioCapture(liveStream = false)
    }

    /** Stops voice-note capture and returns PCM bytes (empty if too short / cancelled). */
    suspend fun stopVoiceNote(): ByteArray {
        if (!_voiceNoteRecording.value && voiceNoteBuffer == null) return ByteArray(0)
        _voiceNoteRecording.value = false
        // Stop the mic so read() unblocks, then join — cancel() was dropping the buffer.
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (_: Exception) {}
        withTimeoutOrNull(1500L) { recordingJob?.join() }
        recordingJob?.cancel()
        recordingJob = null
        cleanUpAudioRecord()
        _audioAmplitude.value = 0f

        val pcm = voiceNoteBuffer?.toByteArray() ?: ByteArray(0)
        voiceNoteBuffer = null
        playChirpEnd()
        Log.d("VoicePttController", "Voice note captured ${pcm.size} bytes")
        // ~80ms minimum
        return if (pcm.size < SAMPLE_RATE * 2 / 12) ByteArray(0) else pcm
    }

    /** Someone else started talking — show UI, but never block local PTT. */
    fun onRemoteSpeaking(speakerName: String, clientId: String? = null) {
        noteRemoteSpeaking(speakerName, clientId)
        if (_pttState.value != PttState.TRANSMITTING && _pttState.value != PttState.REQUESTING) {
            playFloorAlert()
        }
    }

    /** Update speaking label from live audio without chirping on every chunk. */
    fun noteRemoteSpeaking(speakerName: String, clientId: String? = null) {
        if (_pttState.value == PttState.TRANSMITTING || _pttState.value == PttState.REQUESTING) {
            return
        }
        if (clientId != null) activeSpeakerClientId = clientId
        if (_activeSpeakerName.value != speakerName) {
            _activeSpeakerName.value = speakerName
        }
        if (_pttState.value == PttState.BUSY) {
            _pttState.value = PttState.IDLE
        }
        // Audio can arrive without a matching ptt_release (name mismatch / dropped msg).
        scheduleSpeakingClear()
        routePlaybackToSpeaker()
    }

    fun onRemoteStopped(speakerName: String? = null, clientId: String? = null) {
        if (_pttState.value == PttState.TRANSMITTING || _pttState.value == PttState.REQUESTING) {
            return
        }
        // Always clear — name-only matching left the chip stuck when displayName drifted.
        clearRemoteSpeaker()
        if (_pttState.value == PttState.BUSY) {
            _pttState.value = PttState.IDLE
        }
    }

    fun onFloorReleased() {
        val wasTransmitting = _pttState.value == PttState.TRANSMITTING
        _pttState.value = PttState.IDLE
        clearRemoteSpeaker()
        stopAudioCapture()
        // Keep AudioTrack alive so overlapping remote speech isn't cut off.
        if (wasTransmitting) {
            playChirpEnd()
        }
    }

    private fun scheduleSpeakingClear() {
        speakingClearJob?.cancel()
        speakingClearJob = scope.launch {
            delay(SPEAKING_IDLE_MS)
            if (_pttState.value != PttState.TRANSMITTING && _pttState.value != PttState.REQUESTING) {
                clearRemoteSpeaker()
            }
        }
    }

    private fun clearRemoteSpeaker() {
        speakingClearJob?.cancel()
        speakingClearJob = null
        activeSpeakerClientId = null
        _activeSpeakerName.value = null
    }

    /**
     * Streams incoming raw PCM 16-bit mono audio data from another convoy member directly through AudioTrack.
     */
    @Synchronized
    fun playAudioChunk(pcmData: ByteArray, sampleRate: Int = 16000) {
        if (pcmData.isEmpty()) return
        try {
            routePlaybackToSpeaker()
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
            // USAGE_MEDIA + speakerphone: VOICE_COMMUNICATION alone routes to the earpiece,
            // so convoy members hear the PTT indicator/chirp but no actual voice.
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
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

            audioTrack?.setVolume(AudioTrack.getMaxVolume())
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e("VoicePttController", "AudioTrack init error", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun routePlaybackToSpeaker() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = true
        } catch (e: Exception) {
            Log.w("VoicePttController", "Failed to route playback to speaker", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioRouting() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
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

    private fun startAudioCapture(liveStream: Boolean) {
        stopAudioCapture()
        recordingJob = scope.launch {
            var hitVoiceNoteMax = false
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("VoicePttController", "RECORD_AUDIO not granted — PTT indicator will show on peers but no audio is sent")
                    return@launch
                }

                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
                if (minBufferSize <= 0) {
                    Log.e("VoicePttController", "AudioRecord unsupported at ${SAMPLE_RATE}Hz (minBuffer=$minBufferSize)")
                    return@launch
                }
                // Chunks of ~128ms (2048 shorts = 4096 bytes)
                val bufferSize = maxOf(minBufferSize, 4096)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("VoicePttController", "AudioRecord failed to initialize — no audio chunks will be sent")
                    cleanUpAudioRecord()
                    return@launch
                }

                audioRecord?.startRecording()
                Log.d("VoicePttController", if (liveStream) "PTT capture started" else "Voice-note capture started")
                val buffer = ShortArray(2048) // 128ms per chunk
                while (isActive && (
                    (liveStream && _pttState.value == PttState.TRANSMITTING) ||
                        (!liveStream && _voiceNoteRecording.value)
                    )
                ) {
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

                        if (liveStream) {
                            onAudioChunkCaptured?.invoke(byteData)
                        } else {
                            val buf = voiceNoteBuffer ?: break
                            val room = MAX_VOICE_NOTE_BYTES - buf.size()
                            if (room <= 0) {
                                hitVoiceNoteMax = true
                                _voiceNoteRecording.value = false
                                break
                            }
                            if (byteData.size > room) {
                                buf.write(byteData, 0, room)
                                hitVoiceNoteMax = true
                                _voiceNoteRecording.value = false
                                break
                            }
                            buf.write(byteData)
                        }
                    } else if (readShorts < 0) {
                        Log.e("VoicePttController", "AudioRecord.read error: $readShorts")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("VoicePttController", "Audio recording error", e)
            } finally {
                cleanUpAudioRecord()
                _audioAmplitude.value = 0f
            }
            // After capture fully stops — avoids join() deadlock with auto-stop flush
            if (hitVoiceNoteMax) {
                onVoiceNoteAutoStop?.invoke()
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
        _voiceNoteRecording.value = false
        voiceNoteBuffer = null
        stopAudioCapture()
        cleanUpAudioTrack()
        restoreAudioRouting()
        scope.cancel()
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
    }
}
