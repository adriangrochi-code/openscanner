package com.tisma.scanner.cloud

import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Mandar un escaneo a Tisma Cloud de principio a fin: subirlo (o seguir la subida donde quedó), seguir su
 * trabajo en la cola y bajar la pieza. Sin Android: lo usa CloudSendWorker y se prueba contra
 * tools/fake_tisma_cloud.py.
 *
 * Lo hecho se guarda en `store` (el id de la subida y, una vez en la cola, el del trabajo), así que si se
 * corta en cualquier momento, la siguiente vez sigue desde ahí en vez de volver a subir gigas.
 */
class CloudSend(private val client: CloudClient, private val store: Store, private val pollMs: Long = 5_000L) {

    /** Donde se guarda lo hecho entre intentos (SharedPreferences en la app, un mapa en las pruebas). */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    /** La pieza bajada, su informe y las medidas para mostrar («40.20 × 30.20 × 20.30 mm · cerrada»). */
    data class Result(val stl: File, val report: File, val summary: String, val jobId: String)

    /** El trabajo terminó mal en el servidor (no es un corte de red: reintentar no lo arregla). */
    class JobFailed(message: String) : IOException(message)

    fun run(zip: File, onStatus: (String, Float) -> Unit, isCancelled: () -> Boolean = { false }): Result {
        val uploadKey = "scan_" + zip.name + "_" + zip.length()
        val jobKey = "$uploadKey.job"
        var jobId = store.get(jobKey)
        if (jobId == null) {
            var id = store.get(uploadKey)
            var chunk = 16 * 1024 * 1024
            // Sólo se empieza otra subida si el servidor dice que ésta ya no existe (404/410). Un corte de red o un
            // 5xx al preguntar sale como error, para reintentar luego la misma: antes se volvía a subir todo.
            val existing = id?.let {
                try { client.scanStatus(it) }
                catch (e: CloudClient.HttpError) { if (e.code == 404 || e.code == 410) null else throw e }
            }
            jobId = existing?.optString("job_id")?.takeIf { it.isNotBlank() && it != "null" }
            if (jobId == null) {
                if (existing == null) {
                    val (newId, c) = client.create(zip.length(), zip.name)
                    id = newId; chunk = c
                    store.put(uploadKey, id)
                } else {
                    chunk = existing.optInt("chunk_size", chunk)
                }
                jobId = client.upload(id!!, zip, chunk, onProgress = { done, total ->
                    onStatus("Subiendo %.0f / %.0f MB".format(done / 1e6, total / 1e6), done.toFloat() / total)
                }, isCancelled = isCancelled)
            }
            store.put(jobKey, jobId)
            store.put(uploadKey, null)
        }
        val job = client.waitForJob(jobId!!, pollMs, onStatus = { j ->
            when (j.optString("status")) {
                "queued" -> onStatus("En cola en Tisma Cloud…", -1f)
                "running" -> j.optInt("progress").let { onStatus("Procesando en la nube: $it %", it / 100f) }
            }
        }, isCancelled = isCancelled)
        when (job.optString("status")) {
            "failed" -> {
                store.put(jobKey, null)  // el próximo envío empieza de nuevo
                val s = job.optJSONObject("summary")
                throw JobFailed(s?.optString("error_es")?.takeIf { it.isNotBlank() }
                    ?: job.optString("error", "el escaneo falló en el servidor"))
            }
            "cancelled" -> { store.put(jobKey, null); throw JobFailed("el escaneo se canceló") }
        }
        onStatus("Descargando la pieza…", -1f)
        val dir = zip.parentFile!!
        val stl = File(dir, zip.nameWithoutExtension + "_pieza.stl")
        val report = File(dir, zip.nameWithoutExtension + "_informe.json")
        client.downloadResult(jobId, mapOf("pieza.stl" to stl, "pieza_informe.json" to report))
        if (!stl.exists()) throw IOException("el resultado no trae la pieza (pieza.stl)")
        store.put(jobKey, null)
        return Result(stl, report, summaryOf(job.optJSONObject("summary"), report), jobId)
    }

    companion object {
        fun summaryOf(summary: JSONObject?, report: File): String = runCatching {
            // El informe bajado es el completo (con `watertight`); el resumen del trabajo, si no llegó.
            val r = runCatching { JSONObject(report.readText()) }.getOrNull()?.takeIf { it.has("dimensions_mm_xyz") }
                ?: summary!!
            val d = r.getJSONArray("dimensions_mm_xyz")
            "%.2f × %.2f × %.2f mm".format(d.getDouble(0), d.getDouble(1), d.getDouble(2)) +
                (if (r.optBoolean("watertight")) " · cerrada" else "")
        }.getOrDefault("")
    }
}
