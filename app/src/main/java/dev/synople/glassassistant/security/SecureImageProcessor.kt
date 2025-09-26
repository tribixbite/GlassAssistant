package dev.synople.glassassistant.security

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.*
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Secure image processing utility with memory-safe operations and metadata sanitization.
 * Handles image resizing, format conversion, and EXIF data removal.
 */
class SecureImageProcessor(private val context: Context) {

    companion object {
        private const val TAG = "SecureImageProcessor"
        private const val MAX_IMAGE_DIMENSION = 2048
        private const val MAX_IMAGE_FILE_SIZE = 20 * 1024 * 1024 // 20MB
        private const val DEFAULT_QUALITY = 85
        private const val BUFFER_SIZE = 8192
    }

    private val secureFileManager = SecureFileManager.getInstance(context)

    data class ProcessedImage(
        val secureFile: SecureFile,
        val width: Int,
        val height: Int,
        val format: String,
        val sizeBytes: Long
    )

    data class ImageProcessingOptions(
        val maxWidth: Int = MAX_IMAGE_DIMENSION,
        val maxHeight: Int = MAX_IMAGE_DIMENSION,
        val quality: Int = DEFAULT_QUALITY,
        val format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        val stripMetadata: Boolean = true,
        val sanitizeFileName: Boolean = true
    )

    /**
     * Processes an image file securely with memory-safe operations
     */
    fun processImageSecurely(
        inputFile: File,
        options: ImageProcessingOptions = ImageProcessingOptions()
    ): ProcessedImage? {
        var inputStream: InputStream? = null
        var originalBitmap: Bitmap? = null
        var processedBitmap: Bitmap? = null

        try {
            // Validate input file
            if (!validateImageFile(inputFile)) {
                Log.w(TAG, "Invalid image file: ${inputFile.name}")
                return null
            }

            // Check file size
            if (inputFile.length() > MAX_IMAGE_FILE_SIZE) {
                Log.w(TAG, "Image file too large: ${inputFile.length()} bytes")
                return null
            }

            // Read image with memory-efficient options
            val bitmapOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inPreferredConfig = Bitmap.Config.RGB_565 // Use less memory
                inSampleSize = 1
                inTempStorage = ByteArray(BUFFER_SIZE)
            }

            // First pass - get dimensions
            inputStream = FileInputStream(inputFile)
            BitmapFactory.decodeStream(inputStream, null, bitmapOptions)
            inputStream.close()

            if (bitmapOptions.outWidth <= 0 || bitmapOptions.outHeight <= 0) {
                Log.w(TAG, "Invalid image dimensions")
                return null
            }

            // Calculate sample size for memory efficiency
            val sampleSize = calculateSampleSize(
                bitmapOptions.outWidth,
                bitmapOptions.outHeight,
                options.maxWidth,
                options.maxHeight
            )

            // Second pass - decode with sample size
            bitmapOptions.inJustDecodeBounds = false
            bitmapOptions.inSampleSize = sampleSize

            inputStream = FileInputStream(inputFile)
            originalBitmap = BitmapFactory.decodeStream(inputStream, null, bitmapOptions)
            inputStream.close()

            if (originalBitmap == null) {
                Log.w(TAG, "Failed to decode image bitmap")
                return null
            }

            // Check for rotation from EXIF data before stripping
            val rotation = if (options.stripMetadata) {
                getImageRotation(inputFile)
            } else 0

            // Apply rotation if needed
            processedBitmap = if (rotation != 0) {
                rotateImageSafely(originalBitmap, rotation)
            } else {
                originalBitmap
            }

            // Resize if needed
            if (processedBitmap.width > options.maxWidth || processedBitmap.height > options.maxHeight) {
                val resizedBitmap = resizeImageSafely(processedBitmap, options.maxWidth, options.maxHeight)
                if (processedBitmap != originalBitmap) {
                    recycleBitmap(processedBitmap)
                }
                processedBitmap = resizedBitmap
            }

            // Create secure output file
            val outputFormat = when (options.format) {
                Bitmap.CompressFormat.PNG -> "png"
                Bitmap.CompressFormat.WEBP -> "webp"
                else -> "jpg"
            }

            val secureFile = secureFileManager.createSecureTempFile(
                prefix = "processed_image_",
                suffix = ".$outputFormat",
                sensitiveData = true
            )

            // Compress and save to secure file
            secureFile.outputStream().use { outputStream ->
                val compressed = processedBitmap.compress(options.format, options.quality, outputStream)
                if (!compressed) {
                    throw IOException("Failed to compress image")
                }
            }

            val result = ProcessedImage(
                secureFile = secureFile,
                width = processedBitmap.width,
                height = processedBitmap.height,
                format = outputFormat,
                sizeBytes = secureFile.length
            )

            Log.d(TAG, "Successfully processed image: ${result.width}x${result.height}, ${result.sizeBytes} bytes")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
            return null
        } finally {
            // Clean up resources
            inputStream?.close()
            recycleBitmap(originalBitmap)
            if (processedBitmap != originalBitmap) {
                recycleBitmap(processedBitmap)
            }
            System.gc() // Request garbage collection
        }
    }

    /**
     * Converts image to Base64 string safely
     */
    fun imageToBase64(
        inputFile: File,
        options: ImageProcessingOptions = ImageProcessingOptions()
    ): String? {
        val processedImage = processImageSecurely(inputFile, options)

        return processedImage?.use { image ->
            try {
                val imageData = image.secureFile.readBytes()
                Base64.encodeToString(imageData, Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Error converting image to Base64", e)
                null
            }
        }
    }

    /**
     * Creates a secure copy of an image from URI
     */
    fun copyImageFromUri(uri: Uri, options: ImageProcessingOptions = ImageProcessingOptions()): ProcessedImage? {
        var inputStream: InputStream? = null
        var tempFile: File? = null

        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.w(TAG, "Failed to open input stream for URI: $uri")
                return null
            }

            // Create temporary file
            tempFile = File.createTempFile("temp_image_", ".tmp", context.cacheDir)

            // Copy data to temporary file
            tempFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream, BUFFER_SIZE)
            }

            // Process the temporary file
            val result = processImageSecurely(tempFile, options)

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error copying image from URI", e)
            return null
        } finally {
            inputStream?.close()
            tempFile?.let {
                securelyDeleteFile(it, true) // Assume sensitive
            }
        }
    }

    /**
     * Validates image file format and basic properties
     */
    private fun validateImageFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) {
            return false
        }

        val validExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        val extension = file.extension.lowercase()

        if (extension !in validExtensions) {
            return false
        }

        // Basic header validation
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(8)
                stream.read(header)

                when {
                    // JPEG magic numbers
                    header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() -> true
                    // PNG magic numbers
                    header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> true
                    // GIF magic numbers
                    (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte()) -> true
                    // WEBP magic numbers
                    (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
                     header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                     header[8-1] == 0x50.toByte()) -> true // WEBP contains "RIFF" at start and "WEBP" later
                    else -> false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Calculates optimal sample size for memory efficiency
     */
    private fun calculateSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1

        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Resizes bitmap with memory safety
     */
    private fun resizeImageSafely(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val scale = min(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return try {
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory during resize, using original bitmap")
            bitmap
        }
    }

    /**
     * Rotates bitmap safely
     */
    private fun rotateImageSafely(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap

        return try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory during rotation, using original bitmap")
            bitmap
        }
    }

    /**
     * Gets image rotation from EXIF data
     */
    private fun getImageRotation(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF orientation", e)
            0
        }
    }

    /**
     * Safely recycles bitmap to free memory
     */
    private fun recycleBitmap(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            try {
                bitmap.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "Error recycling bitmap", e)
            }
        }
    }

    /**
     * Securely deletes file with overwriting for sensitive data
     */
    private fun securelyDeleteFile(file: File, isSensitive: Boolean) {
        try {
            if (isSensitive && file.exists()) {
                // Overwrite with random data before deletion
                val randomData = ByteArray(min(file.length().toInt(), 8192))
                java.security.SecureRandom().nextBytes(randomData)

                file.writeBytes(randomData)
            }

            file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error securely deleting file", e)
        }
    }

    /**
     * Extension function to safely use ProcessedImage with automatic cleanup
     */
    private inline fun <R> ProcessedImage.use(block: (ProcessedImage) -> R): R {
        return try {
            block(this)
        } finally {
            this.secureFile.close()
        }
    }
}