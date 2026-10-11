/*
 * WClient console hook for the vendored Bedrock protocol modules.
 *
 * Same contract as RakTrace: this module compiles with a Java 8 toolchain and must not depend on the
 * Kotlin trace bus, so the relay installs a sink into it at startup. It covers the batch/compression/
 * encryption/frame layers - the place where a packet stops being a Kotlin object and becomes bytes.
 */
package org.cloudburstmc.protocol.bedrock.netty.trace;

public final class BedrockTrace {

    public static final int LEVEL_TRACE = 0;
    public static final int LEVEL_DEBUG = 1;
    public static final int LEVEL_INFO = 2;
    public static final int LEVEL_WARN = 3;
    public static final int LEVEL_ERROR = 4;

    public interface Sink {
        void log(int level, String tag, String message, String detail);
    }

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

    private BedrockTrace() {
    }

    public static void setSink(Sink sink) {
        BedrockTrace.sink = sink;
    }

    public static void setEnabledFlag(Flag flag) {
        BedrockTrace.enabledFlag = flag == null ? NEVER : flag;
    }

    public static void setVerboseFlag(Flag flag) {
        BedrockTrace.verboseFlag = flag == null ? NEVER : flag;
    }

    public static boolean enabled() {
        return sink != null && enabledFlag.get();
    }

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
        }
    }

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
