package dev.synople.glassassistant.performance

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import dev.synople.glassassistant.security.SecureFileManager
import kotlinx.coroutines.*
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optimized audio recorder with efficient buffer management and low-latency recording
 * designed for Glass hardware constraints and real-time processing requirements.
 */
class OptimizedAudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "OptimizedAudioRecorder"

        // Glass-optimized audio settings
        private const val SAMPLE_RATE = 16000 // Optimal for speech recognition
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION

        // Buffer management
        private const val BUFFER_SIZE_FACTOR = 4 // Multiple of minimum buffer size
        private const val BUFFER_POOL_SIZE = 8   // Number of buffers to pool
        private const val MAX_RECORDING_DURATION_MS = 30000L // 30 seconds max
        private const val SILENCE_THRESHOLD = 1000 // Amplitude threshold for silence detection
        private const val SILENCE_DURATION_MS = 2000L // 2 seconds of silence before auto-stop

        // Performance settings
        private const val PROCESSING_INTERVAL_MS = 100L
        private const val BUFFER_FILL_THRESHOLD = 0.8f // 80% full before processing
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val secureFileManager = SecureFileManager.getInstance(context)
    private var currentOutputFile: dev.synople.glassassistant.security.SecureFile? = null

    // Efficient buffer pool management
    private val bufferPool = ConcurrentLinkedQueue<ByteArray>()
    private val activeBuffers = mutableSetOf<ByteArray>()
    private val bufferSize: Int

    // Audio processing
    private var totalSamples = 0L
    private var silenceSamples = 0L
    private var lastSoundTime = 0L
    private val audioProcessor = AudioProcessor()

    // Performance metrics
    private var recordingStartTime = 0L
    private var bufferUnderruns = 0
    private var totalBytesRecorded = 0L

    init {
        // Calculate optimal buffer size
        bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_FACTOR

        // Pre-populate buffer pool
        repeat(BUFFER_POOL_SIZE) {
            bufferPool.offer(ByteArray(bufferSize))
        }

        Log.d(TAG, "OptimizedAudioRecorder initialized with buffer size: $bufferSize")
    }

    data class RecordingConfig(
        val maxDurationMs: Long = MAX_RECORDING_DURATION_MS,
        val autoStopOnSilence: Boolean = true,
        val silenceThreshold: Int = SILENCE_THRESHOLD,
        val silenceDurationMs: Long = SILENCE_DURATION_MS,
        val enableNoiseReduction: Boolean = true,
        val enableEchoCancellation: Boolean = true
    )

    data class RecordingResult(
        val audioFile: dev.synople.glassassistant.security.SecureFile,
        val durationMs: Long,
        val totalBytes: Long,
        val averageAmplitude: Double,
        val silenceRatio: Float,
        val bufferUnderruns: Int
    )

    /**
     * Starts optimized audio recording
     */
    suspend fun startRecording(config: RecordingConfig = RecordingConfig()): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Recording already in progress")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // Initialize audio recorder
                if (!initializeAudioRecord()) {
                    return@withContext false
                }

                // Create secure output file
                currentOutputFile = secureFileManager.createSecureTempFile(
                    prefix = "audio_recording_",
                    suffix = ".wav",
                    sensitiveData = true
                )

                // Reset metrics
                recordingStartTime = System.currentTimeMillis()
                totalSamples = 0L
                silenceSamples = 0L
                lastSoundTime = recordingStartTime
                bufferUnderruns = 0
                totalBytesRecorded = 0L

                // Start recording
                audioRecord?.startRecording()
                isRecording.set(true)

                Log.d(TAG, "Audio recording started")

                // Start background recording loop
                recordingScope.launch {
                    performRecordingLoop(config)
                }

                true

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                cleanup()
                false
            }
        }
    }

    /**
     * Stops audio recording and returns result
     */
    suspend fun stopRecording(): RecordingResult? {
        if (!isRecording.get()) {
            Log.w(TAG, "No recording in progress")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                isRecording.set(false)

                // Wait for recording loop to finish
                recordingScope.coroutineContext[Job]?.children?.forEach {
                    it.join()
                }

                // Stop audio record
                audioRecord?.stop()

                val outputFile = currentOutputFile
                if (outputFile == null) {
                    Log.w(TAG, "No output file available")
                    return@withContext null
                }

                val durationMs = System.currentTimeMillis() - recordingStartTime
                val silenceRatio = if (totalSamples > 0) {
                    silenceSamples.toFloat() / totalSamples.toFloat()
                } else {
                    0f
                }

                val averageAmplitude = if (totalSamples > 0) {
                    totalBytesRecorded.toDouble() / totalSamples.toDouble()
                } else {
                    0.0
                }

                // Finalize WAV file with proper headers
                finalizeWavFile(outputFile, durationMs)

                val result = RecordingResult(
                    audioFile = outputFile,
                    durationMs = durationMs,
                    totalBytes = totalBytesRecorded,
                    averageAmplitude = averageAmplitude,
                    silenceRatio = silenceRatio,
                    bufferUnderruns = bufferUnderruns
                )

                Log.d(TAG, "Recording completed: ${durationMs}ms, ${totalBytesRecorded} bytes")
                result

            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording", e)
                cleanup()
                null
            }
        }
    }

    /**
     * Pauses recording temporarily
     */
    fun pauseRecording() {
        if (isRecording.get()) {
            isPaused.set(true)
            Log.d(TAG, "Recording paused")
        }
    }

    /**
     * Resumes paused recording
     */
    fun resumeRecording() {
        if (isRecording.get() && isPaused.get()) {
            isPaused.set(false)
            lastSoundTime = System.currentTimeMillis()
            Log.d(TAG, "Recording resumed")
        }
    }

    /**
     * Initializes AudioRecord with optimal settings
     */
    private fun initializeAudioRecord(): Boolean {
        return try {
            audioRecord = AudioRecord(
                AUDIO_SOURCE,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return false
            }

            // Apply audio optimizations if available (API 18+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                try {
                    // Enable automatic gain control for better voice capture
                    if (AutomaticGainControl.isAvailable()) {
                        val agc = AutomaticGainControl.create(audioRecord?.audioSessionId ?: 0)
                        agc?.enabled = true
                        Log.d(TAG, "Automatic Gain Control enabled")
                    }

                    // Enable noise suppression
                    if (NoiseSuppressor.isAvailable()) {
                        val ns = NoiseSuppressor.create(audioRecord?.audioSessionId ?: 0)
                        ns?.enabled = true
                        Log.d(TAG, "Noise Suppression enabled")
                    }

                    // Enable acoustic echo cancellation
                    if (AcousticEchoCanceler.isAvailable()) {
                        val aec = AcousticEchoCanceler.create(audioRecord?.audioSessionId ?: 0)
                        aec?.enabled = true
                        Log.d(TAG, "Acoustic Echo Cancellation enabled")
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply audio optimizations", e)
                }
            }

            true

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioRecord", e)
            false
        }
    }

    /**
     * Main recording loop with efficient buffer management
     */
    private suspend fun performRecordingLoop(config: RecordingConfig) {
        val startTime = System.currentTimeMillis()
        var fileOutputStream: FileOutputStream? = null

        try {
            val outputFile = currentOutputFile?.getFile()
            if (outputFile == null) {
                Log.e(TAG, "No output file for recording")
                return
            }

            fileOutputStream = FileOutputStream(outputFile)

            // Write WAV header (will be updated at end)
            writeWavHeader(fileOutputStream, 0)

            while (isRecording.get()) {
                // Check duration limit
                val currentTime = System.currentTimeMillis()
                if (currentTime - startTime > config.maxDurationMs) {
                    Log.d(TAG, "Recording duration limit reached")
                    break
                }

                // Skip if paused
                if (isPaused.get()) {
                    delay(PROCESSING_INTERVAL_MS)
                    continue
                }

                // Get buffer from pool
                val buffer = getBuffer()

                try {
                    // Read audio data
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (bytesRead > 0) {
                        // Process audio data
                        val amplitude = audioProcessor.processAudioBuffer(
                            buffer,
                            bytesRead,
                            config
                        )

                        // Check for silence
                        val isSilent = amplitude < config.silenceThreshold

                        if (isSilent) {
                            silenceSamples += bytesRead / 2 // 16-bit samples
                        } else {
                            lastSoundTime = currentTime
                        }

                        totalSamples += bytesRead / 2
                        totalBytesRecorded += bytesRead

                        // Write to file
                        fileOutputStream.write(buffer, 0, bytesRead)

                        // Auto-stop on silence if enabled
                        if (config.autoStopOnSilence &&
                            isSilent &&
                            currentTime - lastSoundTime > config.silenceDurationMs) {
                            Log.d(TAG, "Auto-stopping due to silence")
                            break
                        }

                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read error: $bytesRead")
                        bufferUnderruns++

                        if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                            Log.e(TAG, "AudioRecord invalid operation, stopping")
                            break
                        }
                    }

                } finally {
                    // Return buffer to pool
                    returnBuffer(buffer)
                }

                // Small delay to prevent excessive CPU usage
                if (bytesRead <= 0) {
                    delay(10)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in recording loop", e)
        } finally {
            fileOutputStream?.close()
            Log.d(TAG, "Recording loop completed")
        }
    }

    /**
     * Gets buffer from pool or creates new one
     */
    private fun getBuffer(): ByteArray {
        return bufferPool.poll() ?: ByteArray(bufferSize).also {
            Log.d(TAG, "Created new buffer, pool empty")
        }
    }

    /**
     * Returns buffer to pool for reuse
     */
    private fun returnBuffer(buffer: ByteArray) {
        if (bufferPool.size < BUFFER_POOL_SIZE) {
            // Clear buffer before returning to pool
            buffer.fill(0)
            bufferPool.offer(buffer)
        }
    }

    /**
     * Writes WAV file header
     */
    private fun writeWavHeader(outputStream: FileOutputStream, dataSize: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray())

        // fmt chunk
        header.put("fmt ".toByteArray())
        header.putInt(16) // PCM format chunk size
        header.putShort(1) // PCM format
        header.putShort(1) // Mono channel
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2) // Byte rate
        header.putShort(2) // Block align
        header.putShort(16) // Bits per sample

        // data chunk
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())

        outputStream.write(header.array())
    }

    /**
     * Updates WAV file header with correct data size
     */
    private suspend fun finalizeWavFile(
        audioFile: dev.synople.glassassistant.security.SecureFile,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val file = audioFile.getFile()
            val fileSize = file.length()
            val dataSize = fileSize - 44 // Subtract header size

            if (dataSize > 0) {
                // Update header with correct sizes
                file.randomAccessFile("rw").use { raf ->
                    raf.seek(4)
                    raf.writeInt(Integer.reverseBytes((36 + dataSize).toInt()))
                    raf.seek(40)
                    raf.writeInt(Integer.reverseBytes(dataSize.toInt()))
                }

                Log.d(TAG, "WAV file finalized: ${dataSize} bytes, ${durationMs}ms")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing WAV file", e)
        }
    }

    /**
     * Gets recording performance statistics
     */
    fun getPerformanceStats(): Map<String, Any> {
        val isCurrentlyRecording = isRecording.get()
        val currentDuration = if (isCurrentlyRecording) {
            System.currentTimeMillis() - recordingStartTime
        } else {
            0L
        }

        return mapOf(
            "is_recording" to isCurrentlyRecording,
            "is_paused" to isPaused.get(),
            "current_duration_ms" to currentDuration,
            "total_bytes_recorded" to totalBytesRecorded,
            "buffer_pool_size" to bufferPool.size,
            "buffer_underruns" to bufferUnderruns,
            "total_samples" to totalSamples,
            "silence_samples" to silenceSamples,
            "sample_rate" to SAMPLE_RATE,
            "buffer_size" to bufferSize
        )
    }

    /**
     * Releases resources and cleans up
     */
    fun release() {
        try {
            // Stop recording if active
            if (isRecording.get()) {
                recordingScope.launch {
                    stopRecording()
                }
            }

            // Cancel scope
            recordingScope.cancel()

            // Clean up audio record
            audioRecord?.release()
            audioRecord = null

            // Clear buffer pool
            bufferPool.clear()
            activeBuffers.clear()

            // Clean up current file
            currentOutputFile?.close()
            currentOutputFile = null

            Log.d(TAG, "OptimizedAudioRecorder released")

        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio recorder", e)
        }
    }

    private fun cleanup() {
        currentOutputFile?.close()
        currentOutputFile = null
        audioRecord?.release()
        audioRecord = null
    }

    /**
     * Audio processing utilities
     */
    private class AudioProcessor {

        /**
         * Processes audio buffer and returns amplitude
         */
        fun processAudioBuffer(
            buffer: ByteArray,
            bytesRead: Int,
            config: RecordingConfig
        ): Int {
            var maxAmplitude = 0

            // Convert bytes to 16-bit samples and find max amplitude
            for (i in 0 until bytesRead step 2) {
                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF))
                val amplitude = kotlin.math.abs(sample)
                if (amplitude > maxAmplitude) {
                    maxAmplitude = amplitude
                }
            }

            return maxAmplitude
        }
    }
}