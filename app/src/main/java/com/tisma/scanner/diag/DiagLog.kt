package com.tisma.scanner.diag

import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Registro de diagnóstico siempre activo (vista previa congelada, cortes, bloqueos).
 *
 * Dos tipos de línea en el fichero:
 *  - EVENTOS al momento: "12.345 [cam] repetitiva: lock3A=true foco=2.10 dpt".
 *  - RESUMEN cada segundo: "12.0 | pantalla 29 fps (hueco máx 41 ms) · cámara 30 · flujo 30 · …"
 *    con contadores (cuántas veces pasó algo en ese segundo) y máximos (p.ej. el mayor
 *    bloqueo del hilo principal).
 * Así, si la imagen se congela al pulsar Escanear, se ve en qué segundo dejan de llegar
 * fotogramas a la pantalla, si la cámara sigue entregándolos o no, y qué pasó justo antes.
 */
object DiagLog {
    private val t0 = System.nanoTime()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val maxima = ConcurrentHashMap<String, AtomicLong>()
    private val lastAt = ConcurrentHashMap<String, Long>()
    private val recent = ArrayDeque<String>()
    @Volatile private var out: BufferedWriter? = null
    @Volatile var file: File? = null; private set
    @Volatile private var thread: Thread? = null
    /** Datos extra para el resumen (p.ej. escritura pendiente), los pone quien los conoce. */
    @Volatile var extra: () -> String = { "" }

    private fun now() = (System.nanoTime() - t0) / 1e9

    /** Abre un fichero nuevo en `dir` (borra los antiguos: se quedan los 5 últimos). */
    @Synchronized fun start(dir: File) {
        if (out != null) return
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("diag_") }?.sortedByDescending { it.name }?.drop(4)?.forEach { it.delete() }
        val f = File(dir, "diag_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
        file = f
        out = f.bufferedWriter()
        write("Registro de diagnóstico Tisma Scanner · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
        write("Columnas por segundo: pantalla = fotogramas mostrados · cámara = fotogramas que entrega la cámara · " +
            "flujo = fotogramas del velocímetro · principal = mayor bloqueo del hilo de la interfaz")
        thread = Thread({ summaryLoop() }, "diag").apply { isDaemon = true; start() }
        event("app", "registro iniciado")
    }

    @Synchronized private fun write(line: String) {
        recent.addLast(line); while (recent.size > 400) recent.removeFirst()
        runCatching { out?.apply { write(line); newLine() } }
    }

    /** Un evento puntual. */
    fun event(tag: String, msg: String) = write("%.3f [%s] %s".format(Locale.US, now(), tag, msg))

    /** Cuenta una ocurrencia (fotograma, fallo…) en el segundo actual. */
    fun count(name: String, n: Long = 1) {
        counters.getOrPut(name) { AtomicLong() }.addAndGet(n)
    }

    /** Máximo del segundo actual (p.ej. ms de bloqueo). */
    fun max(name: String, v: Long) {
        val a = maxima.getOrPut(name) { AtomicLong() }
        while (true) { val c = a.get(); if (v <= c || a.compareAndSet(c, v)) break }
    }

    /** Marca un instante y registra el hueco desde la marca anterior (máximo por segundo). */
    fun tick(name: String) {
        val t = System.nanoTime()
        lastAt.put(name, t)?.let { max("${name}_gap_ms", (t - it) / 1_000_000) }
        count(name)
    }

    /** Milisegundos desde la última marca `tick(name)` (null si nunca). */
    fun sinceMs(name: String): Long? = lastAt[name]?.let { (System.nanoTime() - it) / 1_000_000 }

    private fun take(map: ConcurrentHashMap<String, AtomicLong>, k: String) = map[k]?.getAndSet(0) ?: 0L

    private fun summaryLoop() {
        // Latido del hilo principal: si la interfaz se bloquea, este mensaje llega tarde.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        while (true) {
            val posted = System.nanoTime()
            main.post { max("main_lag_ms", (System.nanoTime() - posted) / 1_000_000) }
            for (i in 0 until 10) { Thread.sleep(100); val p = System.nanoTime(); main.post { max("main_lag_ms", (System.nanoTime() - p) / 1_000_000) } }
            val rt = Runtime.getRuntime()
            val memMb = (rt.totalMemory() - rt.freeMemory()) / (1 shl 20)
            val screenGap = sinceMs("pantalla")
            val sb = StringBuilder("%.1f | pantalla %d fps (hueco máx %d ms%s) · cámara %d (hueco %d ms) · flujo %d · fallos %d · perdidos %d · principal %d ms · fotos %d · mem %d MB"
                .format(Locale.US, now(), take(counters, "pantalla"), take(maxima, "pantalla_gap_ms"),
                    if (screenGap != null && screenGap > 1000) ", PARADA hace $screenGap ms" else "",
                    take(counters, "cam_frame"), take(maxima, "cam_frame_gap_ms"), take(counters, "flujo"),
                    take(counters, "cam_fail"), take(counters, "cam_buffer_lost"), take(maxima, "main_lag_ms"),
                    take(counters, "jpeg"), memMb))
            val flowMs = take(maxima, "flujo_ms"); if (flowMs > 0) sb.append(" · flujo cálculo máx $flowMs ms")
            val stabMs = take(maxima, "estab_us"); if (stabMs > 0) sb.append(" · estabilización máx $stabMs µs")
            val kf = take(counters, "video_kf"); val fast = take(counters, "video_rapido"); val jpegMs = take(maxima, "jpeg_ms")
            if (kf > 0 || fast > 0) sb.append(" · vídeo: $kf clave, $fast movidos (JPEG máx $jpegMs ms)")
            val rec = take(maxima, "recon_ms"); val skip = take(counters, "recon_saltado")
            if (rec > 0 || skip > 0) sb.append(" · nube: máx $rec ms" + if (skip > 0) ", $skip saltados" else "")
            runCatching { extra() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { sb.append(" · ").append(it) }
            write(sb.toString())
            runCatching { out?.flush() }
        }
    }

    /** Últimas líneas (para mostrarlas o compartir sin fichero). */
    @Synchronized fun tail(n: Int = 60): List<String> = recent.toList().takeLast(n)

    fun flush() { runCatching { out?.flush() } }
}
