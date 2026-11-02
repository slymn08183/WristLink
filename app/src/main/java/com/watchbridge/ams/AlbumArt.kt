package com.watchbridge.ams

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.watchbridge.companion.PhoneImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Cover art for what's playing on the iPhone. AMS tells the watch the artist, album and title
 * but never the artwork, so the WristLink iPhone app looks the album up (iTunes Search) and sends
 * the cover (PhoneImages). Covers are kept on disk, so a track that comes back shows at once.
 */
class AlbumArt(context: Context, media: AmsMediaManager) {

    companion object {
        private const val TAG = "AlbumArt"
        private const val DIR = "album_art"
        private const val MAX_FILES = 80

        /** AMS sends artist, album and title one by one; wait for the set, and skip past skips. */
        private const val SETTLE_MS = 800L
        private const val PHONE_TIMEOUT_MS = 30_000L
    }

    private val dir = File(context.filesDir, DIR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _art = MutableStateFlow<Bitmap?>(null)

    /** The current track's cover, or null while unknown. */
    val art: StateFlow<Bitmap?> = _art.asStateFlow()

    /** Covers the iPhone couldn't find this session; asked again after a restart. */
    private val misses = mutableSetOf<String>()
    private var lookup: Job? = null

    init {
        scope.launch {
            media.state
                .map { Track.of(it) }
                .distinctUntilChanged()
                .collect { onTrack(it) }
        }
    }

    fun release() = scope.cancel()

    private fun onTrack(track: Track?) {
        lookup?.cancel()
        if (track == null) {
            _art.value = null
            return
        }
        val file = File(dir, track.fileName)
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            _art.value = BitmapFactory.decodeFile(file.path)
            return
        }
        _art.value = null
        if (track.key in misses) return

        lookup = scope.launch {
            delay(SETTLE_MS)
            val result = PhoneImages.fetch(
                "art", track.key, PHONE_TIMEOUT_MS,
                mapOf("artist" to track.artist, "album" to track.album, "title" to track.title)
            )
            when (result) {
                is PhoneImages.Result.Image -> {
                    val bitmap = BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size) ?: return@launch
                    dir.mkdirs()
                    file.writeBytes(result.bytes)
                    trim()
                    _art.value = bitmap
                    Log.i(TAG, "Cover for ${track.key}")
                }
                PhoneImages.Result.Missing -> misses += track.key
                null -> Unit
            }
        }
    }

    /** Keep the newest covers only. */
    private fun trim() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    /**
     * What identifies a cover: the album when known (every track on it shares the art),
     * else the track itself.
     */
    private data class Track(val artist: String, val album: String, val title: String) {
        val key: String
            get() = listOf(artist, album.ifEmpty { title }).joinToString("|") { it.trim().lowercase(Locale.ROOT) }

        val fileName: String
            get() = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) } + ".jpg"

        companion object {
            fun of(state: AmsMediaManager.MediaState): Track? =
                if (state.title.isEmpty() || state.artist.isEmpty()) null
                else Track(state.artist, state.album, state.title)
        }
    }
}
