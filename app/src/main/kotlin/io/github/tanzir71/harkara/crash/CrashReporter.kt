package io.github.tanzir71.harkara.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Keeps the last crash on the phone so the user can copy it into a bug report.
 * Nothing is ever sent anywhere automatically.
 */
class CrashReporter(private val crashFile: File, private val versionName: String, private val versionCode: Int) {
    constructor(context: Context, versionName: String, versionCode: Int) :
        this(File(context.filesDir, "crash/last-crash.txt"), versionName, versionCode)

    /** Chains a handler that records the crash, then lets the previous handler finish. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(threadName: String, error: Throwable, at: Instant = Instant.now()) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        crashFile.parentFile?.mkdirs()
        crashFile.writeText("Crash at $at on thread $threadName\n\n$trace")
    }

    /** Text for "Copy crash report": app and phone details, then the last crash if any. */
    fun report(): String = buildString {
        appendLine("Harkara $versionName ($versionCode)")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()
        if (crashFile.exists()) append(crashFile.readText()) else appendLine("No crash recorded.")
    }

    fun clear() {
        crashFile.delete()
    }
}
