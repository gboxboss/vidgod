package com.vidgod.editor.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves crashes and important errors so users can share them for bug reports. */
object Diagnostics {
    private lateinit var dir: File

    fun install(context: Context) {
        dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(dir, "crash.txt").writeText(header(context) + "Thread: ${thread.name}\n" + stack(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun header(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "VidGod $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull()}\n" +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n\n"
    }

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    /** Records a non-fatal error (kept to the last ~100 KB). */
    fun log(context: Context, what: String, e: Throwable? = null) {
        Log.w("VidGod", what, e)
        runCatching {
            val f = File(context.filesDir, "diagnostics/errors.txt")
            f.parentFile?.mkdirs()
            if (f.length() > 100_000) f.writeText(f.readText().takeLast(50_000))
            f.appendText(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " " + what + "\n" + (e?.let { stack(it) } ?: "") + "\n")
        }
    }

    /** Returns and clears the last crash report, if any. */
    fun takeCrash(context: Context): String? {
        val f = File(context.filesDir, "diagnostics/crash.txt")
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text
    }

    fun report(context: Context): String {
        val errors = runCatching { File(context.filesDir, "diagnostics/errors.txt").readText() }.getOrDefault("")
        return header(context) + errors.takeLast(20_000)
    }
}
