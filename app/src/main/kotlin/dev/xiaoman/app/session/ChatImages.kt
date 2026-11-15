package dev.xiaoman.app.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** One chat image is resized before it is sent or kept locally, avoiding provider request limits. */
object ChatImages {
    private const val MAX_EDGE = 1600

    fun readPng(context: Context, uri: Uri): ByteArray? = try {
        val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1))
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        ByteArrayOutputStream().use { out ->
            resized.compress(Bitmap.CompressFormat.PNG, 92, out)
            if (resized !== bitmap) resized.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
            out.toByteArray()
        }
    } catch (_: Exception) { null }

    fun save(directory: File, id: Long, png: ByteArray): String? = try {
        directory.mkdirs()
        val file = File(directory, "$id.png")
        file.writeBytes(png)
        file.absolutePath
    } catch (_: Exception) { null }
}
