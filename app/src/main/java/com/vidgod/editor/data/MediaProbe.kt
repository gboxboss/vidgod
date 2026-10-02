package com.vidgod.editor.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.vidgod.editor.model.MediaKind
import com.vidgod.editor.model.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads duration, size and track information of imported files. */
object MediaProbe {

    suspend fun probe(context: Context, uri: Uri, hintKind: MediaKind? = null): MediaSource? =
        withContext(Dispatchers.IO) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val name = displayName(context, uri)
            val kind = hintKind ?: when {
                mime.startsWith("image/") -> MediaKind.IMAGE
                mime.startsWith("audio/") -> MediaKind.AUDIO
                mime.startsWith("video/") -> MediaKind.VIDEO
                else -> guessKindFromName(name)
            }
            runCatching {
                when (kind) {
                    MediaKind.IMAGE -> probeImage(context, uri, name)
                    MediaKind.AUDIO -> probeAudio(context, uri, name)
                    MediaKind.VIDEO -> probeVideo(context, uri, name)
                }
            }.getOrNull()
        }

    private fun guessKindFromName(name: String): MediaKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp" -> MediaKind.IMAGE
            "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "amr" -> MediaKind.AUDIO
            else -> MediaKind.VIDEO
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment.orEmpty()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    }

    private fun probeImage(context: Context, uri: Uri, name: String): MediaSource {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var w = opts.outWidth
        var h = opts.outHeight
        val rotation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation == 90 || rotation == 270) {
            val t = w; w = h; h = t
        }
        return MediaSource(
            uri = uri.toString(),
            kind = MediaKind.IMAGE,
            name = name,
            durationUs = 3_000_000,
            width = w,
            height = h,
        )
    }

    private fun probeAudio(context: Context, uri: Uri, name: String): MediaSource {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            return MediaSource(
                uri = uri.toString(),
                kind = MediaKind.AUDIO,
                name = title?.takeIf { it.isNotBlank() } ?: name,
                durationUs = dur * 1000,
                hasAudio = true,
            )
        } finally {
            r.release()
        }
    }

    private fun probeVideo(context: Context, uri: Uri, name: String): MediaSource {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w; w = h; h = t
            }
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val isHdr = if (Build.VERSION.SDK_INT >= 30) {
                val transfer = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
                transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084
            } else {
                false
            }
            if (!hasVideo) {
                return MediaSource(uri.toString(), MediaKind.AUDIO, name, dur * 1000, hasAudio = true)
            }
            return MediaSource(
                uri = uri.toString(),
                kind = MediaKind.VIDEO,
                name = name,
                durationUs = dur * 1000,
                width = w,
                height = h,
                hasAudio = hasAudio,
                frameRate = frameRate(context, uri),
                isHdr = isHdr,
            )
        } finally {
            r.release()
        }
    }

    private fun frameRate(context: Context, uri: Uri): Float {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                    f.containsKey(MediaFormat.KEY_FRAME_RATE)
                ) {
                    return runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                }
            }
            30f
        } catch (e: Exception) {
            30f
        } finally {
            ex.release()
        }
    }
}
