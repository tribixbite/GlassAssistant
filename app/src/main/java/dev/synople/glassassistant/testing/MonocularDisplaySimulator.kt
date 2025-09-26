package dev.synople.glassassistant.testing

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import dev.synople.glassassistant.R
import kotlin.math.*

/**
 * Monocular display simulator for testing Glass display functionality.
 * Simulates the Glass prism display characteristics including field of view,
 * brightness levels, and optical distortions for accurate development testing.
 */
class MonocularDisplaySimulator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MonocularDisplaySimulator"

        // Glass display specifications
        private const val GLASS_DISPLAY_WIDTH = 640
        private const val GLASS_DISPLAY_HEIGHT = 360
        private const val GLASS_FIELD_OF_VIEW_DEGREES = 14f
        private const val GLASS_DISTANCE_MM = 20f // Distance from eye

        // Brightness simulation (nits)
        private const val MIN_BRIGHTNESS_NITS = 30
        private const val MAX_BRIGHTNESS_NITS = 5000
        private const val DEFAULT_BRIGHTNESS_NITS = 1000

        // Visual effects
        private const val PRISM_REFRACTION_OFFSET = 2f
        private const val EDGE_VIGNETTING_RADIUS = 0.85f
        private const val COLOR_SHIFT_INTENSITY = 0.1f
        private const val DISTORTION_STRENGTH = 0.02f
    }

    // Display properties
    private var simulatedBrightness = DEFAULT_BRIGHTNESS_NITS
    private var enablePrismEffects = true
    private var enableVignetting = true
    private var enableColorShift = true
    private var enableDistortion = true
    private var showDebugInfo = false

    // Rendering components
    private val displayRect = RectF()
    private val contentBitmap: Bitmap?
        get() = _contentBitmap
    private var _contentBitmap: Bitmap? = null

    // Paint objects for different effects
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val prismPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        isAntiAlias = true
    }

    // Gradient for vignetting effect
    private var vignetteGradient: RadialGradient? = null

    // Content to display
    private var displayContent: String = "Glass Display Test"
    private var overlayElements = mutableListOf<OverlayElement>()

    data class OverlayElement(
        val type: ElementType,
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val color: Int = Color.WHITE,
        val alpha: Int = 255
    )

    enum class ElementType {
        TEXT,
        ICON,
        NOTIFICATION,
        NAVIGATION_ARROW,
        PROGRESS_BAR,
        MENU_ITEM
    }

    init {
        setupSimulator()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateDisplayArea()
        createEffects()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw black background (simulating real world)
        canvas.drawColor(Color.BLACK)

        // Calculate display area scaling
        val scaleX = displayRect.width() / GLASS_DISPLAY_WIDTH
        val scaleY = displayRect.height() / GLASS_DISPLAY_HEIGHT

        canvas.save()
        canvas.translate(displayRect.left, displayRect.top)
        canvas.scale(scaleX, scaleY)

        // Draw the content bitmap or simulate content
        if (contentBitmap != null) {
            drawContentWithEffects(canvas)
        } else {
            drawSimulatedContent(canvas)
        }

        // Draw overlay elements
        drawOverlayElements(canvas)

        canvas.restore()

        // Apply post-processing effects
        applyPrismEffects(canvas)
        applyVignetting(canvas)

        // Draw debug information
        if (showDebugInfo) {
            drawDebugInfo(canvas)
        }
    }

    /**
     * Sets content to display in the simulator
     */
    fun setDisplayContent(content: String) {
        displayContent = content
        invalidate()
    }

    /**
     * Sets content bitmap to display
     */
    fun setContentBitmap(bitmap: Bitmap?) {
        _contentBitmap = bitmap
        invalidate()
    }

    /**
     * Adds overlay element to the display
     */
    fun addOverlayElement(element: OverlayElement) {
        overlayElements.add(element)
        invalidate()
    }

    /**
     * Clears all overlay elements
     */
    fun clearOverlayElements() {
        overlayElements.clear()
        invalidate()
    }

    /**
     * Sets simulated brightness level
     */
    fun setBrightness(nits: Int) {
        simulatedBrightness = nits.coerceIn(MIN_BRIGHTNESS_NITS, MAX_BRIGHTNESS_NITS)
        updateBrightnessEffects()
        invalidate()
    }

    /**
     * Enables or disables prism effects
     */
    fun setPrismEffectsEnabled(enabled: Boolean) {
        enablePrismEffects = enabled
        invalidate()
    }

    /**
     * Enables or disables vignetting effect
     */
    fun setVignettingEnabled(enabled: Boolean) {
        enableVignetting = enabled
        invalidate()
    }

    /**
     * Enables or disables color shift simulation
     */
    fun setColorShiftEnabled(enabled: Boolean) {
        enableColorShift = enabled
        invalidate()
    }

    /**
     * Enables or disables distortion effects
     */
    fun setDistortionEnabled(enabled: Boolean) {
        enableDistortion = enabled
        invalidate()
    }

    /**
     * Shows or hides debug information
     */
    fun setDebugInfoVisible(visible: Boolean) {
        showDebugInfo = visible
        invalidate()
    }

    /**
     * Simulates Glass notification display
     */
    fun simulateNotification(title: String, content: String, duration: Long = 3000) {
        // Add notification overlay
        val notificationElement = OverlayElement(
            type = ElementType.NOTIFICATION,
            text = "$title\n$content",
            x = 50f,
            y = 50f,
            width = GLASS_DISPLAY_WIDTH - 100f,
            height = 80f,
            color = ContextCompat.getColor(context, R.color.glass_blue)
        )

        addOverlayElement(notificationElement)

        // Auto-remove after duration
        postDelayed({
            overlayElements.remove(notificationElement)
            invalidate()
        }, duration)
    }

    /**
     * Simulates navigation display
     */
    fun simulateNavigation(direction: String, distance: String) {
        clearOverlayElements()

        // Direction arrow
        addOverlayElement(OverlayElement(
            type = ElementType.NAVIGATION_ARROW,
            text = direction,
            x = 100f,
            y = GLASS_DISPLAY_HEIGHT / 2f - 50f,
            width = 100f,
            height = 100f,
            color = Color.GREEN
        ))

        // Distance text
        addOverlayElement(OverlayElement(
            type = ElementType.TEXT,
            text = distance,
            x = 250f,
            y = GLASS_DISPLAY_HEIGHT / 2f - 10f,
            width = 200f,
            height = 40f,
            color = Color.WHITE
        ))
    }

    /**
     * Gets current simulator statistics
     */
    fun getSimulatorStats(): Map<String, Any> {
        return mapOf(
            "display_resolution" to "${GLASS_DISPLAY_WIDTH}x${GLASS_DISPLAY_HEIGHT}",
            "field_of_view_degrees" to GLASS_FIELD_OF_VIEW_DEGREES,
            "simulated_brightness_nits" to simulatedBrightness,
            "prism_effects_enabled" to enablePrismEffects,
            "vignetting_enabled" to enableVignetting,
            "color_shift_enabled" to enableColorShift,
            "distortion_enabled" to enableDistortion,
            "overlay_elements_count" to overlayElements.size,
            "debug_info_visible" to showDebugInfo
        )
    }

    /**
     * Initializes simulator components
     */
    private fun setupSimulator() {
        basePaint.isFilterBitmap = true
        prismPaint.isFilterBitmap = true

        updateBrightnessEffects()

        Log.d(TAG, "MonocularDisplaySimulator initialized")
    }

    /**
     * Calculates display area within the view
     */
    private fun calculateDisplayArea() {
        val aspectRatio = GLASS_DISPLAY_WIDTH.toFloat() / GLASS_DISPLAY_HEIGHT.toFloat()
        val viewAspectRatio = width.toFloat() / height.toFloat()

        if (viewAspectRatio > aspectRatio) {
            // View is wider, fit to height
            val displayWidth = height * aspectRatio
            val offsetX = (width - displayWidth) / 2
            displayRect.set(offsetX, 0f, offsetX + displayWidth, height.toFloat())
        } else {
            // View is taller, fit to width
            val displayHeight = width / aspectRatio
            val offsetY = (height - displayHeight) / 2
            displayRect.set(0f, offsetY, width.toFloat(), offsetY + displayHeight)
        }
    }

    /**
     * Creates visual effects gradients and filters
     */
    private fun createEffects() {
        if (displayRect.isEmpty) return

        // Create vignetting gradient
        val centerX = displayRect.width() / 2
        val centerY = displayRect.height() / 2
        val radius = min(centerX, centerY) * EDGE_VIGNETTING_RADIUS

        vignetteGradient = RadialGradient(
            centerX, centerY, radius,
            intArrayOf(Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * Updates brightness-related effects
     */
    private fun updateBrightnessEffects() {
        val brightnessRatio = (simulatedBrightness - MIN_BRIGHTNESS_NITS).toFloat() /
                              (MAX_BRIGHTNESS_NITS - MIN_BRIGHTNESS_NITS).toFloat()

        basePaint.alpha = (255 * brightnessRatio.coerceIn(0.1f, 1f)).toInt()
    }

    /**
     * Draws content with applied effects
     */
    private fun drawContentWithEffects(canvas: Canvas) {
        contentBitmap?.let { bitmap ->
            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = Rect(0, 0, GLASS_DISPLAY_WIDTH, GLASS_DISPLAY_HEIGHT)

            if (enableDistortion) {
                // Apply barrel distortion
                canvas.save()
                val matrix = Matrix()
                // Simple barrel distortion approximation
                matrix.postScale(1f + DISTORTION_STRENGTH, 1f + DISTORTION_STRENGTH,
                                GLASS_DISPLAY_WIDTH / 2f, GLASS_DISPLAY_HEIGHT / 2f)
                canvas.setMatrix(matrix)
            }

            canvas.drawBitmap(bitmap, srcRect, dstRect, basePaint)

            if (enableDistortion) {
                canvas.restore()
            }
        }
    }

    /**
     * Draws simulated content when no bitmap is provided
     */
    private fun drawSimulatedContent(canvas: Canvas) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            textAlign = Paint.Align.CENTER
        }

        // Background
        canvas.drawColor(Color.argb(100, 0, 0, 50))

        // Main content text
        val textX = GLASS_DISPLAY_WIDTH / 2f
        val textY = GLASS_DISPLAY_HEIGHT / 2f

        canvas.drawText(displayContent, textX, textY, textPaint)

        // Glass logo or indicator
        val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.glass_blue)
        }

        canvas.drawCircle(
            GLASS_DISPLAY_WIDTH - 50f,
            50f,
            20f,
            indicatorPaint
        )
    }

    /**
     * Draws overlay elements
     */
    private fun drawOverlayElements(canvas: Canvas) {
        overlayElements.forEach { element ->
            drawOverlayElement(canvas, element)
        }
    }

    /**
     * Draws individual overlay element
     */
    private fun drawOverlayElement(canvas: Canvas, element: OverlayElement) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = element.color
            alpha = element.alpha
        }

        when (element.type) {
            ElementType.TEXT -> {
                paint.textSize = 32f
                canvas.drawText(element.text, element.x, element.y + 32f, paint)
            }

            ElementType.NOTIFICATION -> {
                // Background
                paint.alpha = 128
                canvas.drawRoundRect(
                    element.x, element.y,
                    element.x + element.width, element.y + element.height,
                    10f, 10f, paint
                )

                // Text
                paint.alpha = 255
                paint.textSize = 28f
                val lines = element.text.split("\n")
                lines.forEachIndexed { index, line ->
                    canvas.drawText(
                        line,
                        element.x + 10f,
                        element.y + 30f + (index * 35f),
                        paint
                    )
                }
            }

            ElementType.NAVIGATION_ARROW -> {
                // Simple arrow shape
                val path = Path().apply {
                    moveTo(element.x, element.y + element.height / 2)
                    lineTo(element.x + element.width * 0.7f, element.y)
                    lineTo(element.x + element.width * 0.7f, element.y + element.height * 0.3f)
                    lineTo(element.x + element.width, element.y + element.height * 0.3f)
                    lineTo(element.x + element.width, element.y + element.height * 0.7f)
                    lineTo(element.x + element.width * 0.7f, element.y + element.height * 0.7f)
                    lineTo(element.x + element.width * 0.7f, element.y + element.height)
                    close()
                }

                paint.style = Paint.Style.FILL
                canvas.drawPath(path, paint)
            }

            else -> {
                // Default rectangle
                canvas.drawRect(
                    element.x, element.y,
                    element.x + element.width, element.y + element.height,
                    paint
                )
            }
        }
    }

    /**
     * Applies prism refraction effects
     */
    private fun applyPrismEffects(canvas: Canvas) {
        if (!enablePrismEffects) return

        // Simple chromatic aberration simulation
        if (enableColorShift) {
            val colorMatrix = ColorMatrix().apply {
                val shift = COLOR_SHIFT_INTENSITY
                set(floatArrayOf(
                    1f + shift, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f - shift, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
            }

            val colorFilter = ColorMatrixColorFilter(colorMatrix)
            prismPaint.colorFilter = colorFilter
        }
    }

    /**
     * Applies vignetting effect
     */
    private fun applyVignetting(canvas: Canvas) {
        if (!enableVignetting || vignetteGradient == null) return

        val vignettePaint = Paint().apply {
            shader = vignetteGradient
            alpha = (128 * (1f - simulatedBrightness.toFloat() / MAX_BRIGHTNESS_NITS)).toInt()
        }

        canvas.drawRect(displayRect, vignettePaint)
    }

    /**
     * Draws debug information overlay
     */
    private fun drawDebugInfo(canvas: Canvas) {
        val debugLines = listOf(
            "Glass Display Simulator",
            "Resolution: ${GLASS_DISPLAY_WIDTH}x${GLASS_DISPLAY_HEIGHT}",
            "FOV: ${GLASS_FIELD_OF_VIEW_DEGREES}°",
            "Brightness: ${simulatedBrightness} nits",
            "Overlays: ${overlayElements.size}",
            "Effects: P${if (enablePrismEffects) "+" else "-"} V${if (enableVignetting) "+" else "-"} D${if (enableDistortion) "+" else "-"}"
        )

        val debugBg = Paint().apply {
            color = Color.BLACK
            alpha = 180
        }

        val debugHeight = debugLines.size * 30 + 20
        canvas.drawRect(10f, 10f, 400f, debugHeight.toFloat(), debugBg)

        debugLines.forEachIndexed { index, line ->
            canvas.drawText(line, 20f, 40f + (index * 30f), debugPaint)
        }
    }
}