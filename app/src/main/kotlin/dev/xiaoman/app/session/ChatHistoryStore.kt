package dev.xiaoman.app.session

import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
internal data class ChatHistory(
    val items: List<ChatItem> = emptyList(),
    val active: Boolean = false,
)

class ChatHistoryStore(file: File) {
    private val file = AtomicFile(file)
    private val json = Json { ignoreUnknownKeys = true }

    internal fun load(): ChatHistory = try {
        file.openRead().bufferedReader(Charsets.UTF_8).use {
            json.decodeFromString<ChatHistory>(it.readText())
        }
    } catch (e: IOException) {
        if (file.baseFile.exists()) Log.w(TAG, "Could not read chat history", e)
        ChatHistory()
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Could not decode chat history", e)
        ChatHistory()
    }

    internal fun save(history: ChatHistory): Boolean {
        return try {
            val bytes = json.encodeToString(ChatHistory.serializer(), history).toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try {
                stream.write(bytes)
                file.finishWrite(stream)
                true
            } catch (e: IOException) {
                file.failWrite(stream)
                throw e
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not save chat history", e)
            false
        }
    }

    private companion object {
        const val TAG = "ChatHistoryStore"
    }
}
