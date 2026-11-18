package dev.xiaoman.hands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

/**
 * A grid of frames cut from one image. Rows map to actions (idle/working/success/fail), columns
 * to animation frames. The sheet is decoded at load time and individual frames are sliced
 * lazily so we only keep one Bitmap in memory at a time.
 *
 * Frame counts are *measured from the image*, not assumed: hand-drawn packs come with 4, 5, 6,
 * 7 or 8 frames per row, and slicing a 5-frame sheet as if it had 8 shows two neighbouring
 * frames and an empty gap, which reads as flickering blanks. The grid is inferred from the
 * transparent gutters between frames and falls back to the historical 8-column assumption only
 * when no gutter can be measured.
 */
class PetSpriteSheet private constructor(
    /** Full atlas kept decoded so we can slice frames on demand without re-reading the file. */
    private val atlas: Bitmap,
    /** Horizontal frame count. */
    val cols: Int,
    /** Vertical action-row count. */
    val rows: Int,
    /** Pixel width of one frame on the atlas (rounded down). */
    val frameWidth: Int,
    /** Pixel height of one frame on the atlas (rounded down). */
    val frameHeight: Int,
    /** Which cells actually contain pixels, so empty trailing frames are not played. */
    private val occupied: Array<BooleanArray>,
) : Closeable {

    companion object {
        private const val TAG = "PetSpriteSheet"

        /** Only used when the gutters between frames cannot be measured. */
        private const val FALLBACK_COLS = 8
        private const val MIN_FRAME = SpriteGrid.MIN_FRAME
        private const val MAX_COLS = 32
        private const val MAX_ROWS = 64

        /** Bundled pack ids; kept here so the loader maps ids to asset names in one place. */
        const val BUNDLE_DEFAULT = "default"
        const val BUNDLE_CLASSIC = "classic"

        private fun assetName(bundle: String): String =
            if (bundle == BUNDLE_CLASSIC) "pet_classic.png" else "pet_default.png"

        /** Load a bundled sheet by pack id; unknown ids fall back to the default pack. */
        fun default(context: Context, bundle: String = BUNDLE_DEFAULT): PetSpriteSheet? = runCatching {
            context.assets.open(assetName(bundle)).use { decode(it) }
        }.getOrNull()

        /** Load from a file on disk (user uploads land in private files dir). */
        fun fromFile(file: File): PetSpriteSheet? =
            runCatching { file.inputStream().use { decode(it) } }.getOrNull()

        private fun decode(input: InputStream): PetSpriteSheet? {
            val bmp = runCatching {
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                )
            }.getOrNull() ?: return null
            if (bmp.width < MIN_FRAME || bmp.height < MIN_FRAME) {
                bmp.recycle()
                return null
            }

            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            if (!SpriteGrid.hasTransparency(pixels)) {
                // Fully opaque: a screenshot or a photo, not a spritesheet with gutters between frames.
                bmp.recycle()
                return null
            }


            val cols = SpriteGrid.inferFrameCount(bmp.width, bmp.height, pixels, horizontal = true)
                ?.coerceIn(1, MAX_COLS)
                ?: FALLBACK_COLS
            val frameWidth = bmp.width / cols
            if (frameWidth < MIN_FRAME) {
                bmp.recycle()
                return null
            }
            val estimatedRows = (bmp.height.toFloat() / frameWidth).roundToInt().coerceAtLeast(1)
            val rows = (SpriteGrid.inferFrameCount(bmp.width, bmp.height, pixels, horizontal = false)
                ?: estimatedRows).coerceIn(1, MAX_ROWS)
            val frameHeight = bmp.height / rows
            if (frameHeight < MIN_FRAME) {
                bmp.recycle()
                return null
            }

            val occupied = Array(rows) { row ->
                BooleanArray(cols) { col ->
                    SpriteGrid.cellHasArtwork(pixels, bmp.width, col * frameWidth, row * frameHeight, frameWidth, frameHeight)
                }
            }
            if (occupied.none { cells -> cells.any { it } }) {
                bmp.recycle()
                return null
            }
            return PetSpriteSheet(bmp, cols, rows, frameWidth, frameHeight, occupied)
        }

    }

    /** Slice one frame; the caller owns the returned Bitmap and should recycle it after draw. */
    fun frame(row: Int, col: Int): Bitmap? {
        if (row !in 0 until rows || col !in 0 until cols) return null
        val x = col * frameWidth
        val y = row * frameHeight
        return runCatching { Bitmap.createBitmap(atlas, x, y, frameWidth, frameHeight) }
            .onFailure { Log.w(TAG, "slice failed at ($row,$col): ${it.message}") }
            .getOrNull()
    }

    /** Number of leading frames that actually contain artwork in this row. */
    fun frameCount(row: Int): Int {
        if (row !in 0 until rows) return 0
        val cells = occupied[row]
        var count = 0
        while (count < cells.size && cells[count]) count++
        return count
    }

    fun isUsable(): Boolean = !atlas.isRecycled && frameWidth >= MIN_FRAME && frameHeight >= MIN_FRAME

    override fun close() {
        runCatching { atlas.recycle() }
    }
}
