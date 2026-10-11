package com.retrivedmods.wtrace

import java.io.OutputStream
import java.io.PrintStream

/**
 * Mirrors everything the relay prints (`println`, `System.err`) into the console.
 *
 * The relay is written against plain `println` (it is a JVM library first), so the only way to show
 * those lines in the in-app console is to install an output stream that tees. Lines keep going to the
 * original stream afterwards, so logcat still gets them exactly as before.
 */
object WTraceStreams {

    private const val MAX_LINE = 8_000

    @Volatile
    private var installed = false

    fun install() {
        if (installed) {
            return
        }
        installed = true
        runCatching {
            val stdout = System.out
            val stderr = System.err
            System.setOut(PrintStream(TeeStream(stdout, "out", WLevel.INFO), true))
            System.setErr(PrintStream(TeeStream(stderr, "err", WLevel.WARN), true))
            WTrace.debug("console", "stdout/stderr tee installed", WKind.CODE)
        }.onFailure {
            installed = false
        }
    }

    private class TeeStream(
        private val original: OutputStream,
        private val tag: String,
        private val level: WLevel
    ) : OutputStream() {

        private val line = StringBuilder()

        override fun write(b: Int) {
            safe { original.write(b) }
            feed(b.toChar().toString())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            safe { original.write(b, off, len) }
            feed(String(b, off, len, Charsets.UTF_8))
        }

        override fun flush() {
            safe { original.flush() }
            flushLine()
        }

        override fun close() {
            flushLine()
            safe { original.close() }
        }

        private fun safe(block: () -> Unit) {
            try {
                block()
            } catch (_: Throwable) {
                // Never let the tee break the caller's output.
            }
        }

        private fun feed(chars: String) {
            try {
                synchronized(line) {
                    for (char in chars) {
                        if (char == '\n') {
                            emitLocked()
                        } else {
                            if (line.length < MAX_LINE) {
                                line.append(char)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        private fun flushLine() {
            try {
                synchronized(line) {
                    emitLocked()
                }
            } catch (_: Throwable) {
            }
        }

        private fun emitLocked() {
            val text = line.toString().trimEnd('\r')
            line.setLength(0)
            if (text.isEmpty()) {
                return
            }
            WTrace.log(level, WKind.CODE, tag, text)
        }
    }
}
