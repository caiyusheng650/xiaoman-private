package dev.xiaoman.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the frame-count measurement that replaced the old hard-coded 8 columns: a sheet that
 * is 4, 5, 6 or 7 frames wide has to be detected as such, otherwise the slice job mixes two
 * neighbouring frames with an empty gap and the pet flickers.
 */
class SpriteGridTest {

    /** Builds a sheet the way hand-made packs look: artwork centred in each cell, transparent gutter. */
    private fun sheet(cols: Int, rows: Int, cell: Int, art: Int): Triple<Int, Int, IntArray> {
        val width = cols * cell
        val height = rows * cell
        val pixels = IntArray(width * height)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val inset = (cell - art) / 2
                val left = col * cell + inset
                val top = row * cell + inset
                for (y in top until top + art) {
                    for (x in left until left + art) {
                        pixels[y * width + x] = 0xFF3366CC.toInt()
                    }
                }
            }
        }
        return Triple(width, height, pixels)
    }

    @Test
    fun countsFourToEightColumns() {
        for (cols in 4..8) {
            val (width, height, pixels) = sheet(cols, 4, 160, 110)
            assertEquals("$cols-column sheet", cols, SpriteGrid.inferFrameCount(width, height, pixels, true))
            assertEquals("$cols-column sheet rows", 4, SpriteGrid.inferFrameCount(width, height, pixels, false))
        }
    }

    @Test
    fun countsRowsForAnElongatedAtlas() {
        // The bundled sheet is 8 columns by 11 rows of 192x208 frames.
        val (width, height, pixels) = sheet(8, 11, 192, 168)
        assertEquals(8, SpriteGrid.inferFrameCount(width, height, pixels, true))
    }

    @Test
    fun rejectsSheetsWithoutGutters() {
        val width = 800
        val height = 400
        val pixels = IntArray(width * height) { 0xFF00FF00.toInt() }
        assertNull("artwork touching everywhere has no measurable pitch", SpriteGrid.inferFrameCount(width, height, pixels, true))
    }

    @Test
    fun rejectsFullyOpaqueImages() {
        val width = 800
        val height = 400
        val opaque = IntArray(width * height) { 0xFF00FF00.toInt() }
        assertFalse(SpriteGrid.hasTransparency(opaque))
        val generated = sheet(5, 4, 160, 110).third
        assertTrue(SpriteGrid.hasTransparency(generated))
    }

    @Test
    fun reportsWhichCellsHoldArtwork() {
        val (width, _, pixels) = sheet(5, 4, 160, 110)
        assertTrue(SpriteGrid.cellHasArtwork(pixels, width, 0, 0, 160, 160))
        assertTrue(SpriteGrid.cellHasArtwork(pixels, width, 4 * 160, 3 * 160, 160, 160))
        val emptied = pixels.copyOf()
        for (y in 0 until 160) for (x in 0 until 160) emptied[y * width + x] = 0
        assertFalse(SpriteGrid.cellHasArtwork(emptied, width, 0, 0, 160, 160))
    }
}
