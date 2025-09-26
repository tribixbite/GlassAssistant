package dev.synople.glassassistant.performance

import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.synople.glassassistant.security.SecureFileManager
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Optimized image capture system designed for Glass hardware constraints.
 * Features efficient compression, memory management, and background processing.
 */
class OptimizedImageCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "OptimizedImageCapture"

        // Glass-specific optimizations
        private val GLASS_OPTIMAL_SIZE = Size(1280, 720)
        private val GLASS_PREVIEW_SIZE = Size(640, 360)
        private const val MAX_IMAGE_SIZE = 1024 * 1024 // 1MB max for processing
        private const val JPEG_QUALITY = 85
        private const val COMPRESSION_QUALITY_STEP = 5

        // Performance settings
        private const val CAPTURE_TIMEOUT_MS = 5000L
        private const val PROCESSING_TIMEOUT_MS = 10000L
        private const val MAX_CONCURRENT_CAPTURES = 2
    }

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private val backgroundExecutor: Executor
    private val backgroundHandler: Handler
    private val backgroundThread: HandlerThread = HandlerThread("ImageCaptureThread").apply {
        start()
    }

    private val secureFileManager = SecureFileManager.getInstance(context)
    private val captureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeCaptureJobs = mutableSetOf<Deferred<*>>()

    // Memory-efficient bitmap pool
    private val bitmapPool = BitmapPool(maxSize = 5)

    // Performance metrics
    private var totalCaptureTime = 0L
    private var captureCount = 0

    init {
        backgroundHandler = Handler(backgroundThread.looper)
        backgroundExecutor = ContextCompat.getMainExecutor(context)
    }

    data class CaptureResult(
        val imageData: ByteArray,
        val width: Int,
        val height: Int,
        val compressionRatio: Float,
        val captureTimeMs: Long,
        val processingTimeMs: Long
    )

    data class CaptureConfig(
        val maxWidth: Int = GLASS_OPTIMAL_SIZE.width,
        val maxHeight: Int = GLASS_OPTIMAL_SIZE.height,
        val quality: Int = JPEG_QUALITY,
        val maxFileSize: Int = MAX_IMAGE_SIZE,
        val enableOptimization: Boolean = true,
        val format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG
    )

    /**
     * Initializes camera with optimized settings for Glass
     */
    suspend fun initializeCamera(): Boolean = withContext(Dispatchers.Main) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProvider = cameraProviderFuture.get()

            // Configure image capture with Glass-optimized settings
            val captureConfig = ImageCapture.Builder()
                .setTargetResolution(GLASS_OPTIMAL_SIZE)
                .setJpegQuality(JPEG_QUALITY)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF) // Glass doesn't have flash
                .build()

            // Configure preview for efficient display
            val preview = Preview.Builder()
                .setTargetResolution(GLASS_PREVIEW_SIZE)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Bind camera use cases
            camera = cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                captureConfig
            )

            imageCapture = captureConfig

            Log.d(TAG, "Camera initialized with Glass-optimized settings")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize camera", e)
            false
        }
    }

    /**
     * Captures image with Glass-specific optimizations
     */
    suspend fun captureOptimizedImage(
        config: CaptureConfig = CaptureConfig()
    ): CaptureResult? {

        // Limit concurrent captures
        if (activeCaptureJobs.size >= MAX_CONCURRENT_CAPTURES) {
            Log.w(TAG, "Too many concurrent captures, rejecting request")
            return null
        }

        val captureJob = captureScope.async {
            performOptimizedCapture(config)
        }

        activeCaptureJobs.add(captureJob)

        return try {
            captureJob.await()
        } finally {
            activeCaptureJobs.remove(captureJob)
        }
    }

    /**
     * Performs the actual optimized capture process
     */
    private suspend fun performOptimizedCapture(config: CaptureConfig): CaptureResult? {
        val startTime = System.currentTimeMillis()

        return withContext(Dispatchers.IO) {
            try {
                val imageCapture = this@OptimizedImageCapture.imageCapture
                    ?: throw IllegalStateException("Camera not initialized")

                // Create secure temp file for raw image
                val tempFile = secureFileManager.createSecureTempFile(
                    prefix = "raw_capture_",
                    suffix = ".jpg",
                    sensitiveData = true
                )

                // Capture image to file
                val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile.getFile()).build()

                val captureResult = suspendCancellableCoroutine<Boolean> { continuation ->
                    imageCapture.takePicture(
                        outputOptions,
                        backgroundExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                continuation.resume(true)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                continuation.resumeWithException(exception)
                            }
                        }
                    )
                }

                if (!captureResult) {
                    tempFile.close()
                    return@withContext null
                }

                val captureTime = System.currentTimeMillis() - startTime
                val processingStartTime = System.currentTimeMillis()

                // Process and optimize the captured image
                val processedResult = processImage(tempFile, config)

                val processingTime = System.currentTimeMillis() - processingStartTime

                // Clean up temp file
                tempFile.close()

                if (processedResult != null) {
                    // Update metrics
                    captureCount++
                    totalCaptureTime += captureTime

                    CaptureResult(
                        imageData = processedResult.first,
                        width = processedResult.second.width(),
                        height = processedResult.second.height(),
                        compressionRatio = processedResult.third,
                        captureTimeMs = captureTime,
                        processingTimeMs = processingTime
                    )
                } else {
                    null
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during optimized capture", e)
                null
            }
        }
    }

    /**
     * Processes and optimizes captured image
     */
    private suspend fun processImage(
        imageFile: dev.synople.glassassistant.security.SecureFile,
        config: CaptureConfig
    ): Triple<ByteArray, Rect, Float>? = withContext(Dispatchers.IO) {

        var originalBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null

        try {
            // Decode image with memory-efficient options
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
                inTempStorage = ByteArray(16 * 1024) // 16KB temp storage
            }

            // Get image dimensions
            imageFile.inputStream().use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.w(TAG, "Invalid image dimensions")
                return@withContext null
            }

            // Calculate sample size for memory efficiency
            val sampleSize = calculateSampleSize(
                options.outWidth,
                options.outHeight,
                config.maxWidth,
                config.maxHeight
            )

            // Decode with sample size
            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize

            originalBitmap = imageFile.inputStream().use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (originalBitmap == null) {
                Log.w(TAG, "Failed to decode image")
                return@withContext null
            }

            // Apply optimizations if enabled
            processedBitmap = if (config.enableOptimization) {
                optimizeBitmap(originalBitmap, config)
            } else {
                originalBitmap
            }

            // Compress to target file size
            val compressedData = compressToTargetSize(
                processedBitmap,
                config.format,
                config.quality,
                config.maxFileSize
            )

            val bounds = Rect(0, 0, processedBitmap.width, processedBitmap.height)
            val compressionRatio = compressedData.size.toFloat() /
                (originalBitmap.byteCount.toFloat())

            Triple(compressedData, bounds, compressionRatio)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
            null
        } finally {
            // Clean up bitmaps
            if (originalBitmap != processedBitmap) {
                bitmapPool.recycleBitmap(originalBitmap)
            }
            bitmapPool.recycleBitmap(processedBitmap)
        }
    }

    /**
     * Optimizes bitmap for Glass display and processing
     */
    private fun optimizeBitmap(bitmap: Bitmap, config: CaptureConfig): Bitmap {
        var optimized = bitmap

        try {
            // Resize if needed
            if (bitmap.width > config.maxWidth || bitmap.height > config.maxHeight) {
                val scaledBitmap = scaleBitmapEfficiently(bitmap, config.maxWidth, config.maxHeight)
                if (scaledBitmap != bitmap) {
                    bitmapPool.recycleBitmap(optimized)
                    optimized = scaledBitmap
                }
            }

            // Apply additional optimizations for Glass hardware
            optimized = applyGlassOptimizations(optimized)

        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory during optimization, using original")
            return bitmap
        }

        return optimized
    }

    /**
     * Applies Glass-specific image optimizations
     */
    private fun applyGlassOptimizations(bitmap: Bitmap): Bitmap {
        return try {
            // Create optimized bitmap with Glass-friendly settings
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                isDither = true
            }

            val optimizedBitmap = bitmapPool.getBitmap(
                bitmap.width,
                bitmap.height,
                Bitmap.Config.RGB_565
            )

            val canvas = Canvas(optimizedBitmap)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)

            optimizedBitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply Glass optimizations", e)
            bitmap
        }
    }

    /**
     * Calculates optimal sample size for memory efficiency
     */
    private fun calculateSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight &&
                   (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Efficiently scales bitmap to target size
     */
    private fun scaleBitmapEfficiently(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val ratio = min(
            maxWidth.toFloat() / bitmap.width,
            maxHeight.toFloat() / bitmap.height
        )

        val newWidth = (bitmap.width * ratio).toInt()
        val newHeight = (bitmap.height * ratio).toInt()

        return try {
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory during scaling")
            bitmap
        }
    }

    /**
     * Compresses bitmap to target file size with quality stepping
     */
    private fun compressToTargetSize(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        initialQuality: Int,
        maxSize: Int
    ): ByteArray {
        var quality = initialQuality

        while (quality >= 10) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(format, quality, stream)
            val data = stream.toByteArray()

            if (data.size <= maxSize) {
                Log.d(TAG, "Compressed to ${data.size} bytes at quality $quality")
                return data
            }

            quality -= COMPRESSION_QUALITY_STEP
        }

        // Final attempt at minimum quality
        val stream = ByteArrayOutputStream()
        bitmap.compress(format, 10, stream)
        val data = stream.toByteArray()

        Log.w(TAG, "Final compression: ${data.size} bytes (target: $maxSize)")
        return data
    }

    /**
     * Gets performance statistics
     */
    fun getPerformanceStats(): Map<String, Any> {
        val avgCaptureTime = if (captureCount > 0) totalCaptureTime / captureCount else 0L

        return mapOf(
            "total_captures" to captureCount,
            "avg_capture_time_ms" to avgCaptureTime,
            "active_capture_jobs" to activeCaptureJobs.size,
            "bitmap_pool_size" to bitmapPool.size(),
            "bitmap_pool_hits" to bitmapPool.getHits(),
            "camera_initialized" to (camera != null)
        )
    }

    /**
     * Releases resources
     */
    fun release() {
        try {
            // Cancel active captures
            activeCaptureJobs.forEach { it.cancel() }
            activeCaptureJobs.clear()

            // Cancel scope
            captureScope.cancel()

            // Release camera
            cameraProvider?.unbindAll()
            cameraProvider = null
            camera = null
            imageCapture = null

            // Clean up background thread
            backgroundThread.quitSafely()

            // Clear bitmap pool
            bitmapPool.clear()

            Log.d(TAG, "OptimizedImageCapture released")

        } catch (e: Exception) {
            Log.e(TAG, "Error releasing resources", e)
        }
    }

    /**
     * Simple bitmap pool for memory efficiency
     */
    private class BitmapPool(private val maxSize: Int) {
        private val pool = mutableListOf<Bitmap>()
        private var hits = 0

        fun getBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap {
            // Try to reuse existing bitmap
            val reusable = pool.find { bitmap ->
                bitmap.width == width &&
                bitmap.height == height &&
                bitmap.config == config &&
                !bitmap.isRecycled
            }

            return if (reusable != null) {
                pool.remove(reusable)
                hits++
                reusable
            } else {
                Bitmap.createBitmap(width, height, config)
            }
        }

        fun recycleBitmap(bitmap: Bitmap?) {
            if (bitmap != null && !bitmap.isRecycled && pool.size < maxSize) {
                pool.add(bitmap)
            } else {
                bitmap?.recycle()
            }
        }

        fun size() = pool.size
        fun getHits() = hits
        fun clear() {
            pool.forEach { it.recycle() }
            pool.clear()
        }
    }
}