package com.vidgod.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max

/** Cached video frames and image thumbnails for the timeline and pickers. */
object Thumbnails {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "thumbs").apply { priority = Thread.MIN_PRIORITY } }
        .asCoroutineDispatcher()

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val retrievers = object : LruCache<String, MediaMetadataRetriever>(3) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: MediaMetadataRetriever, newValue: MediaMetadataRetriever?) {
            runCatching { oldValue.release() }
        }
    }

    fun key(uri: String, timeUs: Long, size: Int) = "$uri@${timeUs / 100_000}@$size"

    fun cached(uri: String, timeUs: Long, size: Int): Bitmap? = cache.get(key(uri, timeUs, size))

    /** Frame of a video (or the image itself) with its long side at most [size] px. */
    suspend fun get(context: Context, uri: String, timeUs: Long, size: Int, isImage: Boolean): Bitmap? {
        val k = key(uri, if (isImage) 0 else timeUs, size)
        cache.get(k)?.let { return it }
        return withContext(dispatcher) {
            cache.get(k) ?: runCatching {
                if (isImage) loadImage(context, uri, size) else loadFrame(context, uri, timeUs, size)
            }.getOrNull()?.also { cache.put(k, it) }
        }
    }

    private fun loadFrame(context: Context, uri: String, timeUs: Long, size: Int): Bitmap? {
        val r = retrievers.get(uri) ?: MediaMetadataRetriever().also {
            it.setDataSource(context, Uri.parse(uri))
            retrievers.put(uri, it)
        }
        val frame = if (Build.VERSION.SDK_INT >= 27) {
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: size
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: size
            val scale = size.toFloat() / max(w, h)
            r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
        } else {
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, size) }
        }
        return frame
    }

    /** Exact frame (slower) for freeze frames and colour picking. */
    suspend fun exactFrame(context: Context, uri: String, timeUs: Long, maxSize: Int): Bitmap? = withContext(dispatcher) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Uri.parse(uri))
                val f = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@runCatching null
                scaleDown(f, maxSize)
            } finally {
                r.release()
            }
        }.getOrNull()
    }

    fun loadImage(context: Context, uri: String, size: Int): Bitmap? {
        val u = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val bmp = context.contentResolver.openInputStream(u)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching {
            context.contentResolver.openInputStream(u)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        val rotated = if (rotation != 0) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else {
            bmp
        }
        return scaleDown(rotated, size)
    }

    private fun scaleDown(b: Bitmap, size: Int): Bitmap {
        val longSide = max(b.width, b.height)
        if (longSide <= size) return b
        val s = size.toFloat() / longSide
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
    }
}
