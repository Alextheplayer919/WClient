/*
 * WClient console hook for the vendored RakNet transport.
 *
 * This module is compiled with a Java 8 toolchain (as upstream CloudburstMC is), so it cannot link
 * against the Kotlin trace bus. It exposes a tiny static sink instead; the relay wires it to
 * `com.retrivedmods.wtrace.WTrace` at startup (see WTraceHooks).
 *
 * Call sites must guard expensive work behind enabled() / verbose():
 *
 *     if (RakTrace.enabled()) {
 *         RakTrace.log(RakTrace.LEVEL_DEBUG, "session", "datagram in: " + n);
 *     }
 */
package org.cloudburstmc.netty.trace;

public final class RakTrace {

    public static final int LEVEL_TRACE = 0;
    public static final int LEVEL_DEBUG = 1;
    public static final int LEVEL_INFO = 2;
    public static final int LEVEL_WARN = 3;
    public static final int LEVEL_ERROR = 4;

    /** Sink for RakNet-level events. */
    public interface Sink {
        void log(int level, String tag, String message, String detail);
    }

    /** Late-bound boolean, so the console's verbosity switches keep working after install. */
    public interface Flag {
        boolean get();
    }

    private static final Flag NEVER = new Flag() {
        @Override
        public boolean get() {
            return false;
        }
    };

    private static volatile Sink sink;
    private static volatile Flag enabledFlag = NEVER;
    private static volatile Flag verboseFlag = NEVER;

    private RakTrace() {
    }

    public static void setSink(Sink sink) {
        RakTrace.sink = sink;
    }

    public static void setEnabledFlag(Flag flag) {
        RakTrace.enabledFlag = flag == null ? NEVER : flag;
    }

    public static void setVerboseFlag(Flag flag) {
        RakTrace.verboseFlag = flag == null ? NEVER : flag;
    }

    /** True when RakNet-level tracing is on. */
    public static boolean enabled() {
        return sink != null && enabledFlag.get();
    }

    /** True when per-datagram tracing is on (much noisier than enabled()). */
    public static boolean verbose() {
        return enabled() && verboseFlag.get();
    }

    public static void log(int level, String tag, String message) {
        log(level, tag, message, null);
    }

    public static void log(int level, String tag, String message, String detail) {
        Sink current = sink;
        if (current == null || !enabledFlag.get()) {
            return;
        }
        try {
            current.log(level, tag, message, detail);
        } catch (Throwable ignored) {
            // Tracing must never break the transport.
        }
    }

    /** Entry point for lines that are only worth building when verbose tracing is on. */
    public static void verbose(int level, String tag, String message) {
        Sink current = sink;
        if (current == null || !enabledFlag.get() || !verboseFlag.get()) {
            return;
        }
        try {
            current.log(level, tag, message, null);
        } catch (Throwable ignored) {
        }
    }
}
