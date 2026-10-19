package org.streamdock.app

/** Java linkage failures extend Error, NOT Exception. Convert only those failures,
 * not OOM/StackOverflow/ThreadDeath, into an actionable extraction failure. */
class EngineLoadException(val engine: String, cause: LinkageError) :
    RuntimeException("$engine engine load failed: ${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(400)}", cause)

object EngineBoundary {
    fun <T> call(engine: String, action: () -> T): T {
        try {
            return action()
        } catch (error: LinkageError) {
            throw EngineLoadException(engine, error)
        }
    }
}

object DiagnosticText {
    fun redact(value: String): String = value
        .replace(Regex("https?://[^\\s<>\\\"]+"), "[URL removed]")
        .replace(Regex("(?im)^.*(?:authorization|cookie|token|password|ndus|bdclnd|sekey|\\bsign|\\bpwd)\\s*[=:].*$"), "[redacted]")
        .takeLast(16_000)
}
