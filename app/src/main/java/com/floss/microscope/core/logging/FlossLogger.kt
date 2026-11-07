package com.floss.microscope.core.logging

import android.content.Context
import android.os.Build
import android.util.Log
import com.floss.microscope.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Application-wide diagnostic logger.
 *
 * - Every record goes to logcat (tag prefix `FLOSS/`) when its level passes the threshold.
 * - When verbose logging is enabled, records are also written to a per-launch file under
 *   `filesDir/logs/` (no storage permission needed), so bug reports can be exported from the
 *   Settings screen without a computer attached.
 * - Writes happen on a single background thread; crashes are flushed synchronously.
 * - The threshold is persisted in SharedPreferences. Default: VERBOSE in debug builds, INFO in release.
 */
object FlossLogger {

    enum class Level(val priority: Int, val short: Char) {
        VERBOSE(2, 'V'), DEBUG(3, 'D'), INFO(4, 'I'), WARN(5, 'W'), ERROR(6, 'E')
    }

    private const val PREFS = "floss_diagnostics"
    private const val KEY_VERBOSE = "verbose_logging"
    private const val LOG_DIR = "logs"
    private const val MAX_FILES = 10
    const val TAG_PREFIX = "FLOSS/"

    @Volatile private var threshold: Level = if (BuildConfig.DEBUG) Level.VERBOSE else Level.INFO
    @Volatile private var logFile: File? = null
    @Volatile private var initialised = false

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "floss-log-writer").apply { isDaemon = true }
    }
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    val isVerbose: Boolean get() = threshold.priority <= Level.DEBUG.priority

    /** Current log file for this process launch, or null when file logging is off. */
    val currentLogFile: File? get() = logFile

    /**
     * Call once from Application.onCreate. Safe to call repeatedly; later calls are no-ops.
     */
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val prefs = prefs(context)
        val verbose = prefs.getBoolean(KEY_VERBOSE, BuildConfig.DEBUG)
        threshold = if (verbose) Level.VERBOSE else Level.INFO
        installCrashHandler()
        if (verbose) openFileSink(context)
        logDeviceHeader(context)
    }

    /**
     * Turn verbose (file + DEBUG/VERBOSE) logging on or off and persist the choice.
     */
    fun setVerbose(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VERBOSE, enabled).apply()
        threshold = if (enabled) Level.VERBOSE else Level.INFO
        if (enabled) openFileSink(context) else closeFileSink()
        i("Diagnostics", "verbose logging ${if (enabled) "ENABLED" else "disabled"} (threshold=$threshold)")
    }

    fun v(tag: String, msg: String) = log(Level.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    /** Times [block] and logs its duration at DEBUG level. */
    inline fun <T> timed(tag: String, label: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            if (isVerbose) {
                d(tag, "$label took ${(System.nanoTime() - start) / 1_000_000.0} ms")
            }
        }
    }

    /**
     * Concatenates every retained log file into one text bundle in cacheDir/exports and returns it.
     * Returns null when no log has been written yet.
     */
    fun buildExportBundle(context: Context): File? {
        val dir = File(context.filesDir, LOG_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return null
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val bundle = File(exportDir, "floss-debug-bundle.txt")
        bundle.bufferedWriter().use { out ->
            files.forEach { f ->
                out.write("===== ${f.name} =====\n")
                f.bufferedReader().use { it.copyTo(out) }
                out.write("\n")
            }
        }
        return bundle
    }

    // ---------------------------------------------------------------- internals

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun log(level: Level, tag: String, msg: String, t: Throwable?) {
        if (level.priority < threshold.priority) return
        val fullTag = TAG_PREFIX + tag
        when (level) {
            Level.VERBOSE -> Log.v(fullTag, msg, t)
            Level.DEBUG -> Log.d(fullTag, msg, t)
            Level.INFO -> Log.i(fullTag, msg, t)
            Level.WARN -> Log.w(fullTag, msg, t)
            Level.ERROR -> Log.e(fullTag, msg, t)
        }
        if (logFile != null) {
            val line = formatLine(level, tag, msg, t)
            executor.execute { writeLine(line) }
        }
    }

    private fun formatLine(level: Level, tag: String, msg: String, t: Throwable?): String {
        val sb = StringBuilder(160)
        sb.append(timestampFormat.format(Date())).append(' ')
            .append(level.short).append('/')
            .append(Thread.currentThread().name).append(' ')
            .append(tag).append(": ").append(msg)
        if (t != null) sb.append('\n').append(Log.getStackTraceString(t))
        return sb.append('\n').toString()
    }

    private fun writeLine(line: String) {
        synchronized(lock) {
            try {
                writer?.write(line)
                writer?.flush()
            } catch (ioe: Exception) {
                Log.e(TAG_PREFIX + "Logger", "file write failed: ${ioe.message}")
            }
        }
    }

    private fun openFileSink(context: Context) {
        synchronized(lock) {
            if (writer != null) return
            val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
            pruneOldFiles(dir)
            val file = File(dir, "floss-${fileNameFormat.format(Date())}-${android.os.Process.myPid()}.log")
            logFile = file
            writer = BufferedWriter(FileWriter(file, true))
        }
        Log.i(TAG_PREFIX + "Diagnostics", "file sink opened: ${logFile?.absolutePath}")
    }

    private fun closeFileSink() {
        synchronized(lock) {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
            logFile = null
        }
    }

    private fun pruneOldFiles(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.sortedBy { it.name } ?: return
        if (files.size >= MAX_FILES) {
            files.take(files.size - MAX_FILES + 1).forEach { it.delete() }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val line = formatLine(Level.ERROR, "Crash", "Uncaught exception on ${thread.name}", throwable)
                val future = executor.submit { writeLine(line) }
                future.get(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // best effort: the process is dying anyway
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun logDeviceHeader(context: Context) {
        val pkg = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "unknown"
        }
        i(
            "Diagnostics",
            "app=${BuildConfig.APPLICATION_ID} version=$pkg debug=${BuildConfig.DEBUG} " +
                "device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "abis=${Build.SUPPORTED_ABIS.joinToString()} threshold=$threshold"
        )
    }
}
