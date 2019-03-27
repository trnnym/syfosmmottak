package no.nav.syfo

import io.ktor.client.engine.cio.ConnectException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import no.nav.syfo.metrics.NETWORK_CALL_SUMMARY
import java.io.IOException
import kotlin.reflect.KClass

inline fun <reified T> CoroutineScope.retryAsync(
    callName: String,
    vararg legalExceptions: KClass<out Throwable> = arrayOf(IOException::class, ConnectException::class),
    exceptionCausedByDepth: Int = 3,
    retryIntervals: Array<Long> = arrayOf(500, 1000, 3000, 5000, 10000),
    crossinline block: suspend () -> T
): Deferred<T> = async {
    for (interval in retryIntervals) {
        try {
            return@async timed(callName) { block() }
        } catch (e: Throwable) {
            if (!isCausedBy(e, exceptionCausedByDepth, legalExceptions)) {
                throw e
            } else {
                log.info("Check if ${e::class} is instance of ${legalExceptions.toList()} failed, ${e::class.java.classLoader} vs ${legalExceptions.map { it.java.classLoader } }")
            }
            log.warn("Failed to execute call $callName, retrying in $interval ms", e)
        }
        delay(interval)
    }
    timed(callName) { block() }
}

suspend inline fun <reified T> timed(callName: String, crossinline block: suspend() -> T) = NETWORK_CALL_SUMMARY.labels(callName).startTimer().use {
    block()
}

fun isCausedBy(throwable: Throwable, depth: Int, legalExceptions: Array<out KClass<out Throwable>>): Boolean {
    var current: Throwable = throwable
    for (i in 0.until(depth)) {
        if (legalExceptions.any { it.isInstance(current) }) {
            return true
        }
        current = current.cause ?: break
    }
    return false
}
