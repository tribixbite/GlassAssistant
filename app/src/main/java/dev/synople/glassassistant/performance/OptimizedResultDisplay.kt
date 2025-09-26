package dev.synople.glassassistant.performance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import dev.synople.glassassistant.R
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * Optimized text display view with efficient rendering and view recycling
 * for Glass hardware limitations.
 */
class OptimizedResultDisplay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "OptimizedResultDisplay"
        private const val LINE_HEIGHT_MULTIPLIER = 1.2f
        private const val PADDING_DP = 16
        private const val MAX_VISIBLE_LINES = 15 // Optimized for Glass display
        private const val SCROLL_ANIMATION_DURATION = 150L
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        textSize = resources.getDimensionPixelSize(R.dimen.glass_text_size).toFloat()
        isAntiAlias = true
    }

    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.black)
        alpha = 180 // Semi-transparent background
    }

    private val textBounds = Rect()
    private val paddingPx = (PADDING_DP * resources.displayMetrics.density).toInt()

    private var displayText: String = ""
    private var textLines: List<String> = emptyList()
    private var scrollOffset = 0
    private var maxScrollOffset = 0
    private var lineHeight = 0f

    // View recycling for text measurement
    private val measuredTextCache = mutableMapOf<String, Float>()
    private val recycledRects = ConcurrentLinkedQueue<Rect>()

    init {
        // Pre-calculate line height
        textPaint.getTextBounds("Ag", 0, 2, textBounds)
        lineHeight = textBounds.height() * LINE_HEIGHT_MULTIPLIER

        // Initialize rect pool
        repeat(20) {
            recycledRects.offer(Rect())
        }
    }

    /**
     * Sets the text content with efficient line breaking
     */
    fun setText(text: String) {
        if (displayText == text) return

        displayText = text
        scrollOffset = 0

        // Efficient line breaking with word wrapping
        textLines = breakTextIntoLines(text)
        maxScrollOffset = (textLines.size - MAX_VISIBLE_LINES).coerceAtLeast(0)

        invalidate()
        Log.d(TAG, "Text updated: ${textLines.size} lines, max scroll: $maxScrollOffset")
    }

    /**
     * Scrolls the text content
     */
    fun scroll(direction: Int) {
        val newOffset = when {
            direction > 0 -> min(scrollOffset + 3, maxScrollOffset) // Scroll down
            direction < 0 -> (scrollOffset - 3).coerceAtLeast(0)    // Scroll up
            else -> scrollOffset
        }

        if (newOffset != scrollOffset) {
            scrollOffset = newOffset
            invalidate()
            Log.d(TAG, "Scrolled to offset: $scrollOffset")
        }
    }

    /**
     * Checks if scrolling is possible
     */
    fun canScroll(direction: Int): Boolean {
        return when {
            direction > 0 -> scrollOffset < maxScrollOffset
            direction < 0 -> scrollOffset > 0
            else -> false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (textLines.isEmpty()) return

        // Draw semi-transparent background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        // Calculate visible area
        val availableHeight = height - (paddingPx * 2)
        val maxLines = min(MAX_VISIBLE_LINES, (availableHeight / lineHeight).toInt())
        val startLine = scrollOffset
        val endLine = min(startLine + maxLines, textLines.size)

        // Draw visible text lines
        var currentY = paddingPx + lineHeight

        for (i in startLine until endLine) {
            val line = textLines[i]

            // Use recycled rect for bounds calculation
            val bounds = recycledRects.poll() ?: Rect()
            textPaint.getTextBounds(line, 0, line.length, bounds)

            // Center text horizontally
            val textX = (width - bounds.width()) / 2f

            canvas.drawText(line, textX, currentY, textPaint)
            currentY += lineHeight

            // Return rect to pool
            recycledRects.offer(bounds)
        }

        // Draw scroll indicators
        drawScrollIndicators(canvas, startLine, endLine)
    }

    /**
     * Draws scroll indicators if needed
     */
    private fun drawScrollIndicators(canvas: Canvas, startLine: Int, endLine: Int) {
        val indicatorPaint = Paint().apply {
            color = ContextCompat.getColor(context, R.color.white)
            alpha = 100
        }

        val indicatorWidth = 4 * resources.displayMetrics.density
        val indicatorHeight = 20 * resources.displayMetrics.density

        // Top indicator (can scroll up)
        if (startLine > 0) {
            canvas.drawRect(
                width - indicatorWidth - paddingPx,
                paddingPx.toFloat(),
                width - paddingPx.toFloat(),
                paddingPx + indicatorHeight,
                indicatorPaint
            )
        }

        // Bottom indicator (can scroll down)
        if (endLine < textLines.size) {
            canvas.drawRect(
                width - indicatorWidth - paddingPx,
                height - paddingPx - indicatorHeight,
                width - paddingPx.toFloat(),
                height - paddingPx.toFloat(),
                indicatorPaint
            )
        }
    }

    /**
     * Efficiently breaks text into lines with word wrapping
     */
    private fun breakTextIntoLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        val words = text.split("\\s+".toRegex())
        val availableWidth = width - (paddingPx * 2)

        if (availableWidth <= 0) {
            // Fallback if view not measured yet
            return text.split('\n')
        }

        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) {
                word
            } else {
                "$currentLine $word"
            }

            val lineWidth = getTextWidth(testLine)

            if (lineWidth <= availableWidth) {
                currentLine = StringBuilder(testLine)
            } else {
                // Add current line and start new one
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }

                // Handle very long words
                if (getTextWidth(word) > availableWidth) {
                    lines.addAll(breakLongWord(word, availableWidth))
                    currentLine = StringBuilder()
                } else {
                    currentLine = StringBuilder(word)
                }
            }
        }

        // Add remaining text
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return lines
    }

    /**
     * Breaks very long words that don't fit in a line
     */
    private fun breakLongWord(word: String, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        var remaining = word

        while (remaining.isNotEmpty()) {
            var endIndex = remaining.length

            // Binary search for maximum fitting length
            var start = 1
            var end = remaining.length

            while (start <= end) {
                val mid = (start + end) / 2
                val testText = remaining.substring(0, mid)

                if (getTextWidth(testText) <= maxWidth) {
                    start = mid + 1
                    endIndex = mid
                } else {
                    end = mid - 1
                }
            }

            if (endIndex <= 0) endIndex = 1 // At least one character

            result.add(remaining.substring(0, endIndex))
            remaining = remaining.substring(endIndex)
        }

        return result
    }

    /**
     * Gets text width with caching for performance
     */
    private fun getTextWidth(text: String): Float {
        return measuredTextCache.getOrPut(text) {
            textPaint.measureText(text)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Clear cache when size changes
        measuredTextCache.clear()

        // Re-break text if we have content
        if (displayText.isNotEmpty()) {
            textLines = breakTextIntoLines(displayText)
            maxScrollOffset = (textLines.size - MAX_VISIBLE_LINES).coerceAtLeast(0)
            scrollOffset = scrollOffset.coerceAtMost(maxScrollOffset)
            invalidate()
        }
    }

    /**
     * Clears caches to free memory
     */
    fun clearCaches() {
        measuredTextCache.clear()
        Log.d(TAG, "Display caches cleared")
    }

    /**
     * Gets current display statistics
     */
    fun getDisplayStats(): Map<String, Any> {
        return mapOf(
            "total_lines" to textLines.size,
            "visible_lines" to min(MAX_VISIBLE_LINES, textLines.size),
            "scroll_offset" to scrollOffset,
            "max_scroll_offset" to maxScrollOffset,
            "cache_size" to measuredTextCache.size,
            "can_scroll_up" to canScroll(-1),
            "can_scroll_down" to canScroll(1)
        )
    }
}