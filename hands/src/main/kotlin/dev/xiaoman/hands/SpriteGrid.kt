package dev.xiaoman.hands

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Works out how many frames a hand-drawn spritesheet actually holds.
 *
 * Hand-made packs do not all use 8 columns: 4, 5, 6 and 7 are common. Slicing a 5-frame sheet
 * as if it had 8 cuts two neighbouring frames plus an empty gap into one frame, which shows up
 * as flickering blanks. The count is therefore measured from the transparent gutters between
 * frames instead of assumed.
 *
 * Kept free of Android types so it can be unit-tested on the JVM.
 */
object SpriteGrid {

    /** Pixels below this alpha count as background, not artwork. */
    const val ALPHA_FLOOR = 8

    /** A frame narrower than this is not worth showing. */
    const val MIN_FRAME = 8

    /** Never trust a measurement outside these bounds. */
    const val MAX_COUNT = 64

    /**
     * Counts evenly spaced content bands along one axis. Returns null when the bands touch
     * (no gutter to measure) or when the spacing is too irregular to trust.
     */
    fun inferFrameCount(width: Int, height: Int, pixels: IntArray, horizontal: Boolean): Int? {
        val length = if (horizontal) width else height
        val depth = if (horizontal) height else width
        if (length <= 0 || depth <= 0 || pixels.size < width * height) return null

        val starts = ArrayList<Int>()
        var inBand = false
        for (axis in 0 until length) {
            var hasArtwork = false
            for (other in 0 until depth) {
                val argb = if (horizontal) pixels[other * width + axis] else pixels[axis * width + other]
                if ((argb ushr 24) > ALPHA_FLOOR) {
                    hasArtwork = true
                    break
                }
            }
            if (hasArtwork && !inBand) {
                starts += axis
                inBand = true
            } else if (!hasArtwork) {
                inBand = false
            }
        }
        // Fewer than two bands means the frames touch, so there is no gutter to measure.
        if (starts.size < 2) return null

        val steps = starts.zipWithNext { a, b -> b - a }
        val pitch = steps.sorted()[steps.size / 2]
        if (pitch < MIN_FRAME) return null
        val tolerance = max(MIN_FRAME, (pitch * 0.35f).toInt())
        if (steps.any { abs(it - pitch) > tolerance }) return null

        val count = (length.toFloat() / pitch).roundToInt()
        if (count < 1 || count > MAX_COUNT) return null
        // The first band does not start at 0, so compare pitch × count against the full length.
        if (abs(count * pitch - length) > length / 7) return null
        return count
    }

    /**
     * True when the image has at least one transparent pixel. Every real spritesheet is built on
     * transparency (that is what the gutters between frames are), so a fully opaque image - a
     * screenshot or a photo - is not one, and slicing it would just show arbitrary fragments.
     */
    fun hasTransparency(pixels: IntArray): Boolean {
        for (argb in pixels) if ((argb ushr 24) <= ALPHA_FLOOR) return true
        return false
    }

    /** True when any pixel of the cell is opaque enough to be artwork. */
    fun cellHasArtwork(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Boolean {
        val height = pixels.size / width
        val xEnd = minOf(left + cellWidth, width)
        val yEnd = minOf(top + cellHeight, height)
        for (y in max(0, top) until yEnd) {
            val base = y * width
            for (x in max(0, left) until xEnd) {
                if ((pixels[base + x] ushr 24) > ALPHA_FLOOR) return true
            }
        }
        return false
    }
}
