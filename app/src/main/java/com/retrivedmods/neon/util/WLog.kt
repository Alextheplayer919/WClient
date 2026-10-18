package com.retrivedmods.neon.util

import com.retrivedmods.neon.trace.WKind
import com.retrivedmods.neon.trace.WLevel
import com.retrivedmods.neon.trace.WTrace

/**
 * `android.util.Log` plus the in-app console.
 *
 * Every line still goes to logcat exactly as before, and additionally into [WTrace], so the console
 * shows what the app and its modules were doing. Use it through an import alias to keep call sites
 * unchanged:
 *
 *     import com.retrivedmods.neon.util.WLog as Log
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object WLog {

    fun v(tag: String, message: String) = write(WLevel.TRACE, tag, message, null)

    fun d(tag: String, message: String) = write(WLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = write(WLevel.INFO, tag, message, null)

    fun w(tag: String, message: String) = write(WLevel.WARN, tag, message, null)

    fun e(tag: String, message: String) = write(WLevel.ERROR, tag, message, null)

    fun v(tag: String, message: String, throwable: Throwable) = write(WLevel.TRACE, tag, message, throwable)

    fun d(tag: String, message: String, throwable: Throwable) = write(WLevel.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable) = write(WLevel.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable) = write(WLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable) = write(WLevel.ERROR, tag, message, throwable)

    /** Logs a Throwable with its stack trace into the console. */
    fun throwable(tag: String, message: String, throwable: Throwable) =
        write(WLevel.ERROR, tag, message, throwable)

    private fun write(level: WLevel, tag: String, message: String, throwable: Throwable?) {
        // Keep logcat identical to before the console existed.
        runCatching {
            when (level) {
                WLevel.TRACE -> if (throwable == null) android.util.Log.v(tag, message) else android.util.Log.v(tag, message, throwable)
                WLevel.DEBUG -> if (throwable == null) android.util.Log.d(tag, message) else android.util.Log.d(tag, message, throwable)
                WLevel.INFO -> if (throwable == null) android.util.Log.i(tag, message) else android.util.Log.i(tag, message, throwable)
                WLevel.WARN -> if (throwable == null) android.util.Log.w(tag, message) else android.util.Log.w(tag, message, throwable)
                WLevel.ERROR -> if (throwable == null) android.util.Log.e(tag, message) else android.util.Log.e(tag, message, throwable)
            }
        }
        WTrace.log(level, WKind.CODE, tag, message, throwable?.stackTraceToString())
    }
}
