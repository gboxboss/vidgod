package com.vidgod.editor.model

/**
 * Speed curves ("speed ramps"). A curve is a list of control points over the source range of a
 * clip; it is evaluated as a piecewise-constant function with [SEGMENTS] segments so that the
 * timeline duration can be computed exactly and the player gets a finite number of speed changes.
 */
object SpeedCurves {
    const val SEGMENTS = 24

    data class Preset(val name: String, val points: List<SpeedPoint>)

    val presets = listOf(
        Preset("Montage", pts(0f to 1f, 0.2f to 3f, 0.45f to 0.5f, 0.55f to 0.5f, 0.8f to 3f, 1f to 1f)),
        Preset("Hero", pts(0f to 2.5f, 0.35f to 2.5f, 0.45f to 0.3f, 0.6f to 0.3f, 0.7f to 2.5f, 1f to 2.5f)),
        Preset("Bullet", pts(0f to 3f, 0.3f to 3f, 0.4f to 0.2f, 0.7f to 0.2f, 0.8f to 3f, 1f to 3f)),
        Preset("Jump cut", pts(0f to 1f, 0.3f to 1f, 0.32f to 6f, 0.5f to 6f, 0.52f to 1f, 1f to 1f)),
        Preset("Flash in", pts(0f to 6f, 0.4f to 4f, 0.6f to 1f, 1f to 1f)),
        Preset("Flash out", pts(0f to 1f, 0.4f to 1f, 0.6f to 4f, 1f to 6f)),
        Preset("Slow mo end", pts(0f to 1f, 0.6f to 1f, 0.8f to 0.3f, 1f to 0.3f)),
        Preset("Ramp up", pts(0f to 0.5f, 1f to 4f)),
    )

    private fun pts(vararg p: Pair<Float, Float>) = p.map { SpeedPoint(it.first, it.second) }

    /** Speed at normalised position [pos] (0..1) by linear interpolation of the control points. */
    fun valueAt(points: List<SpeedPoint>, pos: Float): Float {
        if (points.isEmpty()) return 1f
        val sorted = points.sortedBy { it.pos }
        if (pos <= sorted.first().pos) return sorted.first().speed
        if (pos >= sorted.last().pos) return sorted.last().speed
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (pos >= a.pos && pos <= b.pos) {
                val f = if (b.pos - a.pos < 1e-6f) 0f else (pos - a.pos) / (b.pos - a.pos)
                return a.speed + (b.speed - a.speed) * f
            }
        }
        return 1f
    }

    /** Speed of each of the [SEGMENTS] constant-speed segments. */
    fun segmentSpeeds(points: List<SpeedPoint>): FloatArray =
        FloatArray(SEGMENTS) { i ->
            valueAt(points, (i + 0.5f) / SEGMENTS).coerceIn(0.1f, 100f)
        }

    /** Start of segment [i] in source time relative to the clip start. */
    fun segmentStartUs(rangeUs: Long, i: Int): Long = rangeUs * i / SEGMENTS

    fun timelineDuration(rangeUs: Long, points: List<SpeedPoint>): Long {
        val speeds = segmentSpeeds(points)
        var total = 0.0
        for (i in 0 until SEGMENTS) {
            val len = segmentStartUs(rangeUs, i + 1) - segmentStartUs(rangeUs, i)
            total += len / speeds[i].toDouble()
        }
        return total.toLong().coerceAtLeast(1)
    }

    /** Maps a timeline offset (from clip start) to a source offset (from trim start). */
    fun timelineToSource(rangeUs: Long, points: List<SpeedPoint>, timelineUs: Long): Long {
        val speeds = segmentSpeeds(points)
        var remaining = timelineUs.toDouble()
        for (i in 0 until SEGMENTS) {
            val start = segmentStartUs(rangeUs, i)
            val len = segmentStartUs(rangeUs, i + 1) - start
            val segTimeline = len / speeds[i].toDouble()
            if (remaining <= segTimeline) return start + (remaining * speeds[i]).toLong()
            remaining -= segTimeline
        }
        return rangeUs
    }

    /** Maps a source offset (from trim start) to a timeline offset (from clip start). */
    fun sourceToTimeline(rangeUs: Long, points: List<SpeedPoint>, sourceUs: Long): Long {
        val speeds = segmentSpeeds(points)
        var total = 0.0
        for (i in 0 until SEGMENTS) {
            val start = segmentStartUs(rangeUs, i)
            val end = segmentStartUs(rangeUs, i + 1)
            if (sourceUs <= end) {
                total += (sourceUs - start).coerceAtLeast(0) / speeds[i].toDouble()
                return total.toLong()
            }
            total += (end - start) / speeds[i].toDouble()
        }
        return total.toLong()
    }
}

/** Timeline <-> source time helpers for visual clips. */
fun VisualClip.timelineToSourceUs(offsetUs: Long): Long {
    val o = offsetUs.coerceIn(0, durationUs)
    if (o >= durationUs) return trimEndUs
    return when {
        isImage -> trimStartUs + o
        speedCurve.isNotEmpty() -> trimStartUs + SpeedCurves.timelineToSource(sourceRangeUs, speedCurve, o)
        else -> trimStartUs + (o * speed.toDouble()).toLong()
    }
}

fun VisualClip.sourceToTimelineUs(sourceUs: Long): Long {
    val s = (sourceUs - trimStartUs).coerceIn(0, sourceRangeUs)
    return when {
        isImage -> s
        speedCurve.isNotEmpty() -> SpeedCurves.sourceToTimeline(sourceRangeUs, speedCurve, s)
        else -> (s / speed.toDouble()).toLong()
    }
}
