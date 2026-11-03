package com.tisma.scanner.session

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Trabajo que se adelanta MIENTRAS se graba, con la potencia que sobra: un solo hilo de
 * prioridad baja y una cola por prioridad (lo que más sirve primero). Al detener se cierra la
 * entrada y se espera a que termine lo que está en marcha, para que el procesado final no
 * repita nada (corregir dos veces una foto la estropearía).
 */
class IdleWorker(name: String = "preproceso", private val background: Boolean = true) {
    enum class Priority { HIGH, NORMAL, LOW }

    private val seq = AtomicLong()
    private inner class Task(val prio: Priority, val n: Long, val body: () -> Unit) : Runnable, Comparable<Task> {
        override fun run() { if (accepting) runCatching(body) }
        override fun compareTo(other: Task) = compareValuesBy(this, other, { it.prio.ordinal }, { it.n })
    }

    @Volatile var accepting = true; private set
    private val pool = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>()) { r ->
        Thread({
            // Prioridad de "segundo plano" de Android (la cámara y el seguimiento van antes).
            if (background) runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            r.run()
        }, name).apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }

    /** Encola un trabajo (se descarta si la entrada ya está cerrada). */
    fun submit(prio: Priority, body: () -> Unit) {
        if (accepting) pool.execute(Task(prio, seq.incrementAndGet(), body))
    }

    /** Trabajos esperando (sin contar el que está en marcha). */
    val queued get() = pool.queue.size

    /** Vuelve a aceptar trabajos (nuevo escaneo). */
    fun open() { accepting = true }

    /**
     * Cierra la entrada y espera a que acabe lo que está en marcha (lo encolado se descarta).
     * Después el procesado final puede hacer el resto sin pisarse con nada.
     */
    fun closeAndDrain(timeoutMs: Long = 60_000) {
        accepting = false
        pool.queue.clear()
        val done = java.util.concurrent.CountDownLatch(1)
        pool.execute(object : Runnable, Comparable<Any> {
            override fun run() = done.countDown()
            override fun compareTo(other: Any) = 0
        })
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun shutdown() { accepting = false; pool.shutdownNow() }
}
