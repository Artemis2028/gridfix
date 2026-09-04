package app.gridfix.android

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Catch a fatal crash, keep it on the phone, and offer it to the user next launch.
 *
 * Why this exists at all, given Android vitals already collects crashes: vitals only
 * sees installs from Play, only from users who opted in to sharing diagnostics, and it
 * never carries the one thing that makes a report actionable - what the person was
 * doing. During a closed test that gap is the difference between "0.3% crash rate" and
 * a fix.
 *
 * Two rules this class must not break:
 *
 *  1. **It always hands the crash on to the previous handler.** Play's handler is what
 *     puts the crash in vitals, deobfuscated against the mapping in the AAB. Swallowing
 *     it here would trade an automatic, aggregated, symbolicated report for one email a
 *     tester may never send. This is an addition, never a replacement.
 *  2. **Nothing leaves the phone on its own.** The trace is written to app-private
 *     storage and stays there until the user taps Send, which opens their own mail app
 *     with the text in the body. The app has no servers to send it to and this does not
 *     change that.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /** Enough to identify any crash; a mailto body much past this starts to be refused. */
    private const val MAX_CHARS = 4000

    /**
     * Install the handler. Called once, as early as possible, so a crash during startup
     * is caught too.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Anything thrown in here would replace the real crash with a worse one.
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved report, or null when the last run ended normally. */
    fun pending(context: Context): String? = runCatching {
        val f = File(context.filesDir, FILE_NAME)
        if (f.isFile && f.length() > 0L) f.readText() else null
    }.getOrNull()

    /** Forget it, whether it was sent or discarded — it is only ever offered once. */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val text = buildString {
            append("MGRS GPS ").append(BuildConfig.VERSION_NAME)
            append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" — Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Thread: ").append(thread.name).append('\n')
            append("When:   ").append(stamp).append('\n')
            append('\n')
            append(trace)
        }
        File(context.filesDir, FILE_NAME).writeText(
            if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n… trace truncated"
        )
    }
}
