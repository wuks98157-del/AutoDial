package com.autodial

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight uncaught-exception handler that appends crash reports to a file
 * in the app's external files dir. Intended for alpha testing — testers can
 * pull the file via adb or a file manager and attach it to a bug report.
 *
 * File path: `/Android/data/com.autodial/files/crash-log.txt`
 * (visible under Internal Storage → Android → data → com.autodial → files
 * on most file managers, and `adb pull /sdcard/Android/data/com.autodial/files/crash-log.txt`
 * from a host machine).
 *
 * Does NOT replace the default handler — calls through to it so Android still
 * shows the "X has stopped" dialog and terminates the process. Only adds the
 * persistent file copy.
 */
object CrashLogger {

    private const val TAG = "AutoDial"
    private const val FILENAME = "crash-log.txt"
    private const val MAX_BYTES = 256 * 1024  // ~256KB; older entries get truncated

    fun install(context: Context) {
        val appContext = context.applicationContext
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "CrashLogger: failed to write crash", t)
            }
            existing?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "CrashLogger installed → ${logFile(appContext).absolutePath}")
    }

    fun logFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILENAME)
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val file = logFile(context)
        // Keep file bounded — if it's big enough that adding a new crash would
        // blow past MAX_BYTES, truncate to last half. Bounded-loss is fine for
        // alpha diagnostics.
        if (file.exists() && file.length() > MAX_BYTES) {
            val tail = file.readText().takeLast(MAX_BYTES / 2)
            file.writeText(tail)
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val stackTrace = StringWriter().also { sw ->
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
        }.toString()
        val entry = buildString {
            append("\n\n===== ").append(stamp).append(" =====\n")
            append("thread=").append(thread.name).append(" (id=").append(thread.id).append(")\n")
            append("version=").append(BuildConfig.VERSION_NAME).append(" (code=").append(BuildConfig.VERSION_CODE).append(")\n")
            append("android=").append(android.os.Build.VERSION.RELEASE).append(" sdk=").append(android.os.Build.VERSION.SDK_INT).append("\n")
            append("device=").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL).append("\n")
            append("\n")
            append(stackTrace)
        }
        file.appendText(entry)
    }
}
