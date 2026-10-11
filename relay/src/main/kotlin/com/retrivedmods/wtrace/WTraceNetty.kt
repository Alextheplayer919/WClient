package com.retrivedmods.wtrace

import io.netty.util.internal.logging.InternalLogLevel
import io.netty.util.internal.logging.InternalLogger
import io.netty.util.internal.logging.InternalLoggerFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/** Bridges Netty's internal logger API and java.util.logging into the in-app trace console. */
object WTraceNetty {

    private val installLock = Any()

    @Volatile
    private var installed = false

    private val julHandler = object : Handler() {
        init {
            level = Level.ALL
            formatter = SimpleFormatter()
        }

        override fun publish(record: LogRecord) {
            if (!isLoggable(record)) {
                return
            }

            val traceLevel = when {
                record.level.intValue() >= Level.SEVERE.intValue() -> WLevel.ERROR
                record.level.intValue() >= Level.WARNING.intValue() -> WLevel.WARN
                record.level.intValue() >= Level.INFO.intValue() -> WLevel.INFO
                record.level.intValue() >= Level.FINE.intValue() -> WLevel.DEBUG
                else -> WLevel.TRACE
            }
            if (!WTrace.accepts(traceLevel, WKind.NET)) {
                return
            }

            val message = runCatching { formatter.formatMessage(record) }
                .getOrElse { record.message ?: "(empty JUL message)" }
            val detail = record.thrown?.stackTraceToString()
            val loggerName = record.loggerName ?: record.sourceClassName ?: "java.util.logging"
            WTrace.log(traceLevel, WKind.NET, loggerName, message, detail)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    /** Install before any relay transport classes create their static Netty loggers. */
    fun install() {
        if (installed) {
            return
        }

        synchronized(installLock) {
            if (installed) {
                return
            }

            InternalLoggerFactory.setDefaultFactory(TraceLoggerFactory())

            val rootLogger = Logger.getLogger("")
            if (rootLogger.handlers.none { it === julHandler }) {
                rootLogger.addHandler(julHandler)
            }
            installed = true
        }
    }

    private class TraceLoggerFactory : InternalLoggerFactory() {
        override fun newInstance(name: String): InternalLogger =
            Proxy.newProxyInstance(
                InternalLogger::class.java.classLoader,
                arrayOf<Class<*>>(InternalLogger::class.java),
                TraceLoggerInvocationHandler(name)
            ) as InternalLogger
    }

    /**
     * Implement the current InternalLogger surface dynamically. Netty has added overloads over time;
     * a proxy keeps enabled checks and log routing aligned with whatever methods the bundled version
     * invokes without a second hand-written adapter to drift out of date.
     */
    private class TraceLoggerInvocationHandler(private val loggerName: String) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: emptyArray()
            return when (method.name) {
                "name", "getName" -> loggerName
                "toString" -> "WTraceInternalLogger($loggerName)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                "isEnabled" -> WTrace.accepts(levelFor(method.name, arguments), WKind.NET)
                else -> when {
                    method.name.startsWith("is") && method.name.endsWith("Enabled") ->
                        WTrace.accepts(levelFor(method.name, arguments), WKind.NET)

                    isLogMethod(method.name) -> {
                        log(method.name, arguments)
                        null
                    }

                    method.returnType == java.lang.Boolean.TYPE -> false
                    method.returnType == java.lang.Integer.TYPE -> 0
                    method.returnType == java.lang.Long.TYPE -> 0L
                    method.returnType == java.lang.Float.TYPE -> 0f
                    method.returnType == java.lang.Double.TYPE -> 0.0
                    method.returnType == java.lang.Short.TYPE -> 0.toShort()
                    method.returnType == java.lang.Byte.TYPE -> 0.toByte()
                    method.returnType == java.lang.Character.TYPE -> '\u0000'
                    else -> null
                }
            }
        }

        private fun isLogMethod(name: String): Boolean =
            name in setOf("trace", "debug", "info", "warn", "error", "log")

        private fun levelFor(methodName: String, args: Array<out Any?>): WLevel {
            val explicitLevel = args.firstOrNull() as? InternalLogLevel
            if (explicitLevel != null) {
                return when (explicitLevel) {
                    InternalLogLevel.TRACE -> WLevel.TRACE
                    InternalLogLevel.DEBUG -> WLevel.DEBUG
                    InternalLogLevel.INFO -> WLevel.INFO
                    InternalLogLevel.WARN -> WLevel.WARN
                    InternalLogLevel.ERROR -> WLevel.ERROR
                }
            }

            return when (methodName.removePrefix("is").removeSuffix("Enabled").lowercase()) {
                "trace" -> WLevel.TRACE
                "debug" -> WLevel.DEBUG
                "info" -> WLevel.INFO
                "warn" -> WLevel.WARN
                "error" -> WLevel.ERROR
                else -> WLevel.INFO
            }
        }

        private fun log(methodName: String, args: Array<out Any?>) {
            val explicitLevel = args.firstOrNull() as? InternalLogLevel
            val offset = if (methodName == "log" && explicitLevel != null) 1 else 0
            val flattenedArgs = args.drop(offset).flatMap { value ->
                if (value is Array<*>) value.toList() else listOf(value)
            }.toMutableList()

            val throwable = flattenedArgs.lastOrNull() as? Throwable
            if (throwable != null) {
                flattenedArgs.removeAt(flattenedArgs.lastIndex)
            }

            val message = if (flattenedArgs.isEmpty()) {
                throwable?.toString() ?: ""
            } else {
                val template = flattenedArgs.removeAt(0)?.toString() ?: "null"
                formatMessage(template, flattenedArgs)
            }
            val traceLevel = levelFor(methodName, args)
            WTrace.log(traceLevel, WKind.NET, loggerName, message, throwable?.stackTraceToString())
        }

        private fun formatMessage(template: String, parameters: List<Any?>): String {
            if (parameters.isEmpty()) {
                return template
            }

            val result = StringBuilder(template.length + parameters.size * 8)
            var parameterIndex = 0
            var index = 0
            while (index < template.length) {
                if (template[index] == '\\' && index + 2 < template.length &&
                    template[index + 1] == '{' && template[index + 2] == '}'
                ) {
                    result.append("{}")
                    index += 3
                } else if (template[index] == '{' && index + 1 < template.length &&
                    template[index + 1] == '}' && parameterIndex < parameters.size
                ) {
                    result.append(parameters[parameterIndex++]?.toString() ?: "null")
                    index += 2
                } else {
                    result.append(template[index++])
                }
            }

            if (parameterIndex < parameters.size) {
                result.append(" [")
                result.append(parameters.drop(parameterIndex).joinToString(", ") { it?.toString() ?: "null" })
                result.append(']')
            }
            return result.toString()
        }
    }
}
